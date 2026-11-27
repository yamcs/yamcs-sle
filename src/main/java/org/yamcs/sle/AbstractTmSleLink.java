package org.yamcs.sle;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.Spec;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.actions.ActionResult;
import org.yamcs.jsle.AntennaId;
import org.yamcs.jsle.CcsdsTime;
import org.yamcs.jsle.Constants.DeliveryMode;
import org.yamcs.jsle.Constants.FrameQuality;
import org.yamcs.jsle.Constants.LockStatus;
import org.yamcs.jsle.Constants.ProductionStatus;
import org.yamcs.jsle.Constants.RequestedFrameQuality;
import org.yamcs.jsle.GVCID;
import org.yamcs.jsle.Isp1Handler;
import org.yamcs.jsle.ParameterName;
import org.yamcs.jsle.RacfSleMonitor;
import org.yamcs.jsle.SleException;
import org.yamcs.jsle.SleParameter;
import org.yamcs.jsle.user.FrameConsumer;
import org.yamcs.jsle.user.RacfServiceUserHandler;
import org.yamcs.jsle.user.RacfStatusReport;
import org.yamcs.jsle.user.RafServiceUserHandler;
import org.yamcs.jsle.user.RcfServiceUserHandler;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.sle.EndpointHandler.Endpoint;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.LinkAction;
import org.yamcs.tctm.ParameterDataLink;
import org.yamcs.tctm.ParameterSink;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.time.Instant;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.EnumeratedParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SystemParameter;
import org.yamcs.xtce.util.AggregateMemberNames;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;

import com.google.gson.JsonObject;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Abstract class for the TM SLE links.
 * <p>
 * It implements the common functionality for Offline and Online links.
 */
public abstract class AbstractTmSleLink extends AbstractTmFrameLink
        implements FrameConsumer, SleLink, ParameterDataLink {
    String packetPreprocessorClassName;
    Object packetPreprocessorArgs;
    // the current connection; a connection stops being current when the link is disabled or the connection is lost.
    // Set only in connectAndBind and cleared via takeHandler/clearIfCurrent (synchronized), at most one at a time.
    volatile RacfServiceUserHandler rsuh;
    EndpointHandler endpointHandler;

    SleConfig sconf;

    DeliveryMode deliveryMode;

    String service;

    org.yamcs.jsle.State sleState = org.yamcs.jsle.State.UNBOUND;

    org.yamcs.jsle.State requestedState = org.yamcs.jsle.State.UNBOUND;

    private volatile ParameterValue rafStatus;

    // added in version 1.6.0:
    // privateAnnotationParameter option can be configured to specify the name of a
    // parameter that will contain the value of the private annotation property that is sent by the SLE provider
    // together with each frame
    protected ParameterSink parameterSink;
    private Parameter privateAnnotationParameter;
    private Stream privateAnnotationStream;

    int paramSeq = 0;

    private SystemParameter sp_sleState, sp_racfStatus;
    final static AggregateMemberNames rafStatusMembers = AggregateMemberNames.get(new String[] { "productionStatus",
            "carrierLockStatus", "subcarrierLockStatus", "symbolSyncLockStatus", "deliveredFrameNumber",
            "errorFreeFrameNumber" });

    // if null-> RAF, otherwise RCF
    GVCID gvcid = null;
    private RequestedFrameQuality frameQuality;

    boolean startSleOnEnable;

    LinkAction startAction = new LinkAction("start", "Start SLE") {
        @Override
        public void execute(Link link, JsonObject jsonObject, ActionResult result) {
            if (requestedState == org.yamcs.jsle.State.UNBOUND) {
                connectAndBind(true);
            } else {
                sleStart();
            }
            result.complete();
        }
    };
    LinkAction stopAction = new LinkAction("stop", "Stop SLE") {
        @Override
        public void execute(Link link, JsonObject jsonObject, ActionResult result) {
            sleStop();
            result.complete();
        }
    };

    @Override
    public Spec getDefaultSpec() {
        var spec = super.getDefaultSpec();
        spec.addOption("sleProvider", OptionType.STRING).withRequired(true);
        spec.addOption("service", OptionType.STRING).withChoices("RAF", "RCF").withDefault("RAF");
        spec.addOption("rcfTfVersion", OptionType.INTEGER);
        spec.addOption("rcfSpacecraftId", OptionType.INTEGER);
        spec.addOption("rcfVcId", OptionType.INTEGER);
        spec.addOption("frameQuality", OptionType.STRING).withChoices(RequestedFrameQuality.class);
        spec.addOption("privateAnnotationParameter", OptionType.STRING).withDefault(false).withDescription(
                "If set, provide the frame annotation received from SLE as a parameter witht the given fqn");
        spec.addOption("privateAnnotationStream", OptionType.STRING).withDefault(false).withDescription(
                "If set, provide the frame annotation received from SLE as a paacket on this stream. The stream has to be created before.");
        spec.addOption("startSleOnEnable", OptionType.BOOLEAN).withDefault(true)
                .withDescription("Whether the SLE session should automatically be STARTed when the link is enabled."
                        + " If false, enabling the link will only BIND it (and it has to be started using the provided action)");
        return spec;
    }

    /**
     * parses and validates configuration
     *
     * @param deliveryMode
     *
     * @throws ConfigurationException
     *             if port is not defined in the configuration
     */
    public void init(String instance, String name, YConfiguration config, DeliveryMode deliveryMode)
            throws ConfigurationException {
        super.init(instance, name, config);
        this.deliveryMode = deliveryMode;

        YConfiguration slec = YConfiguration.getConfiguration("sle").getConfig("Providers")
                .getConfig(config.getString("sleProvider"));
        this.startSleOnEnable = config.getBoolean("startSleOnEnable", true);

        service = config.getString("service", "RAF");
        gvcid = null;
        if ("RCF".equals(service)) {
            int tfVersion = config.getInt("rcfTfVersion", frameHandler.getFrameType().getVersion());
            int spacecraftId = config.getInt("rcfSpacecraftId", frameHandler.getSpacecraftId());
            int vcId = config.getInt("rcfVcId", -1);
            gvcid = new GVCID(tfVersion, spacecraftId, vcId);
        } else if ("RAF".equals(service)) {
            this.frameQuality = config.getEnum("frameQuality", RequestedFrameQuality.class,
                    RequestedFrameQuality.goodFramesOnly);
        } else {
            throw new ConfigurationException("Invalid service '" + service + "' specified. Use one of RAF or RCF");
        }
        String type;
        switch (deliveryMode) {
        case rtnCompleteOnline:
            type = gvcid == null ? "raf-onlc" : "rcf-onlc";
            break;
        case rtnTimelyOnline:
            type = gvcid == null ? "raf-onlt" : "rcf-onlt";
            break;
        case rtnOffline:
            type = gvcid == null ? "raf-offl" : "rcf-offl";
            break;
        default:
            throw new ConfigurationException("Invalid delivery mode " + deliveryMode + " for this data link");

        }
        this.sconf = new SleConfig(slec, type);
        this.endpointHandler = new EndpointHandler(sconf);

        String pafqn = config.getString("privateAnnotationParameter", null);
        if (pafqn != null) {
            var mdb = YamcsServer.getServer().getInstance(yamcsInstance).getMdb();
            privateAnnotationParameter = mdb.getParameter(pafqn);
            if (privateAnnotationParameter == null) {
                throw new ConfigurationException("Cannot find in the MDB the parameter with the name " + pafqn);
            }
            /*  if (!config.containsKey(LinkManager.PP_STREAM_KEY)) {
                throw new ConfigurationException("privateAnnotationParameter configuration requires also the "
                        + LinkManager.PP_STREAM_KEY + " option");
            }*/
        }

        String pastream = config.getString("privateAnnotationStream", null);
        if (pastream != null) {
            var ydb = YarchDatabase.getInstance(instance);
            privateAnnotationStream = ydb.getStream(pastream);
            if (privateAnnotationStream == null) {
                throw new ConfigurationException("Cannot find a stream named " + pastream);
            }
        }

    }

    /**
     * Connects to the provider and binds, unless the link is disabled or there is already a connection.
     * <p>
     * The second condition matters because reconnections are scheduled from several places (connection lost,
     * connection failed, start action); a second connection would leave the first one bound to the service instance
     * and never closed, after which the provider refuses all binds with 'already bound' (yamcs-sle#16).
     */
    protected synchronized void connectAndBind(boolean startSle) {
        if (!isRunningAndEnabled()) {
            return;
        }
        if (rsuh != null) {
            log.debug("Already connected or connecting to the SLE provider");
            return;
        }
        requestedState = org.yamcs.jsle.State.READY;

        Endpoint endpoint = endpointHandler.next();

        eventProducer
                .sendInfo("Connecting to SLE " + service + " service " + endpoint.getHost() + ":" + endpoint.getPort()
                        + " as user " + sconf.auth.getMyUsername());
        RacfServiceUserHandler h;
        if (gvcid == null) {
            h = new RafServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
            ((RafServiceUserHandler) h).setRequestedFrameQuality(frameQuality);
        } else {
            h = new RcfServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
        }
        h.setVersionNumber(sconf.versionNumber);
        h.setAuthLevel(sconf.authLevel);
        h.setReturnTimeoutSec(sconf.returnTimeoutSec);
        h.addMonitor(new MyMonitor(h));
        rsuh = h;

        NioEventLoopGroup workerGroup = getEventLoop();
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, sconf.connectionTimeoutMillis);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(sconf.tmlMaxLength, 4, 4));
                ch.pipeline().addLast(new Isp1Handler(true, sconf.hbSettings));
                ch.pipeline().addLast(h);
            }
        });

        ChannelFuture cf = b.connect(endpoint.getHost(), endpoint.getPort());
        cf.addListener(f -> {
            if (!f.isSuccess()) {
                if (!clearIfCurrent(h)) {
                    return; // the link has been disabled in the meantime
                }
                eventProducer.sendWarning("Failed to connect to the SLE provider: " + f.cause().getMessage());
                long nextAttempt = endpointHandler.nextConnectionAttemptMillis();

                if (nextAttempt >= 0) {
                    workerGroup.schedule(() -> connectAndBind(startSle), nextAttempt, TimeUnit.MILLISECONDS);
                }
            } else if (rsuh != h) {
                // the link has been disabled while connecting; h.shutdown() does nothing before the channel is active
                cf.channel().close();
            } else {
                sleBind(h, startSle);
            }
        });
    }

    /**
     * Clears {@link #rsuh} if it is h
     *
     * @return true if h was the current connection
     */
    protected synchronized boolean clearIfCurrent(RacfServiceUserHandler h) {
        if (rsuh == h) {
            rsuh = null;
            return true;
        }
        return false;
    }

    /**
     * Gracefully closes the current connection (if any); from now on its callbacks are ignored.
     */
    protected void closeConnection() {
        RacfServiceUserHandler h;
        synchronized (this) {
            h = rsuh;
            rsuh = null;
        }
        if (h != null) {
            Utils.sleStop(h, sconf, eventProducer);
        }
        sleState = org.yamcs.jsle.State.UNBOUND;
    }

    private void sleBind(RacfServiceUserHandler h, boolean startSle) {
        requestedState = org.yamcs.jsle.State.READY;

        h.bind().handle((v, t) -> {
            if (t != null) {
                if (rsuh == h) {
                    // the disconnection triggers a new attempt after reconnectionIntervalSec
                    eventProducer.sendWarning("Failed to bind: " + t.getMessage() + "; closing the connection");
                }
                h.shutdown();
                return null;
            }
            if (startSle && rsuh == h) {
                sleStart();
            }
            return null;
        });
    }

    abstract void sleStart();

    void sleStop() {
        RacfServiceUserHandler h = rsuh;
        if (h == null) {
            return;
        }
        requestedState = org.yamcs.jsle.State.READY;
        log.debug("Stopping SLE service");

        h.stop().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to stop: " + t);
                return null;
            }
            log.debug("Successfully stopped the service");
            return null;
        });
    }

    /**
     * Verifies that the requested state is the same as the current state
     * 
     * @return Status.OK or Status.UNAVAIL
     */
    @Override
    protected Status connectionStatus() {
        return sleState != org.yamcs.jsle.State.UNBOUND && sleState == requestedState ? Status.OK : Status.UNAVAIL;
    }

    @Override
    public String getDetailedStatus() {
        return "SLE " + sleState;
    }

    @Override
    public Map<String, Object> getExtraInfo() {
        return Map.of("SLE state", sleState);
    }

    @Override
    public void acceptFrame(CcsdsTime ert, AntennaId antennaId, int dataLinkContinuity,
            FrameQuality frameQuality, byte[] privAnn, byte[] data) {
        if (isDisabled()) {
            log.debug("Ignoring frame received while disabled");
            return;
        }

        int length = data.length;
        dataIn(1, length);

        if (log.isTraceEnabled()) {
            log.trace("Received frame length: {}, data: {}, privAnn: {}", data.length,
                    StringConverter.arrayToHexString(data),
                    privAnn == null ? "null" : StringConverter.arrayToHexString(privAnn));
        }
        try {
            Instant ertime = toInstant(ert);

            if (length < frameHandler.getMinFrameSize()) {
                eventProducer.sendWarning("Error processing frame: size " + length
                        + " shorter than minimum allowed " + frameHandler.getMinFrameSize());
                invalidFrameCount.incrementAndGet();
            } else if (length > frameHandler.getMaxFrameSize()) {
                eventProducer.sendWarning("Error processing frame: size " + length + " longer than maximum allowed "
                        + frameHandler.getMaxFrameSize());
                invalidFrameCount.incrementAndGet();
            } else {
                validFrameCount.incrementAndGet();

                frameHandler.handleFrame(ertime, data, 0, length);
            }

            if (privAnn != null) {
                if (privateAnnotationParameter != null) {

                    var pv = new ParameterValue(privateAnnotationParameter);
                    pv.setAcquisitionTime(timeService.getMissionTime());
                    pv.setGenerationTime(ertime.getMillis());
                    pv.setRawValue(ValueUtility.getBinaryValue(privAnn));
                    parameterSink.updateParameters(ertime.getMillis(), "SLE", paramSeq++, Arrays.asList(pv));
                }
                if (privateAnnotationStream != null) {
                    Tuple t = new Tuple();
                    t.addColumn(StandardTupleDefinitions.GENTIME_COLUMN, DataType.TIMESTAMP, ertime.getMillis());
                    t.addColumn(StandardTupleDefinitions.SEQNUM_COLUMN, DataType.INT, (int) dataInCount.get());
                    t.addColumn(StandardTupleDefinitions.TM_RECTIME_COLUMN, timeService.getMissionTime());
                    t.addColumn(StandardTupleDefinitions.TM_STATUS_COLUMN, DataType.INT, 0);
                    t.addColumn(StandardTupleDefinitions.TM_PACKET_COLUMN, DataType.BINARY, privAnn);
                    t.addColumn(StandardTupleDefinitions.TM_ERTIME_COLUMN, DataType.HRES_TIMESTAMP, ertime);

                    privateAnnotationStream.emitTuple(t);
                }
            }

        } catch (TcTmException e) {
            eventProducer.sendWarning("Error processing frame: " + e.toString());
        } catch (Exception e) {
            log.error("Error processing frame", e);
        }

    }

    @Override
    public void onExcessiveDataBacklog() {
        eventProducer.sendWarning("Excessive Data Backlog reported by the SLE provider");
    }

    @Override
    public void onProductionStatusChange(ProductionStatus productionStatusChange) {
        eventProducer.sendInfo("SLE production status changed to " + productionStatusChange);

    }

    @Override
    public void onLossFrameSync(CcsdsTime time, LockStatus carrier, LockStatus subcarrier, LockStatus symbolSync) {
        eventProducer.sendInfo("SLE loss frame sync time: " + time + " carrier: " + carrier + " subcarrier: "
                + subcarrier + " symbolSync: " + symbolSync);
    }

    @Override
    public void setupSystemParameters(SystemParametersService sps) {
        super.setupSystemParameters(sps);
        sp_sleState = sps.createEnumeratedSystemParameter(linkName + "/sleState", org.yamcs.jsle.State.class,
                "The state of the SLE connection");

        EnumeratedParameterType lockStatusType = sps.createEnumeratedParameterType(LockStatus.class);
        AggregateParameterType spStatusType = new AggregateParameterType.Builder()
                .setName("racfStatus_type")
                .addMember(new Member("productionStatus",
                        sps.createEnumeratedParameterType(ProductionStatus.class)))
                .addMember(new Member("carrierLockStatus", lockStatusType))
                .addMember(new Member("subcarrierLockStatus", lockStatusType))
                .addMember(new Member("symbolSyncLockStatus", lockStatusType))
                .addMember(new Member("deliveredFrameNumber", sps.getBasicType(Type.SINT32)))
                .addMember(new Member("errorFreeFrameNumber", sps.getBasicType(Type.SINT32)))
                .build();

        String pname = linkName + (gvcid == null ? "/rafStatus" : "/rcfStatus");
        sp_racfStatus = sps.createSystemParameter(pname, spStatusType,
                ((gvcid == null) ? "RAF" : "RCF") + " service status received from the Ground Station");
    }

    @Override
    protected void collectSystemParameters(long time, List<ParameterValue> list) {
        super.collectSystemParameters(time, list);
        list.add(SystemParametersService.getPV(sp_sleState, time, sleState.name()));
        if (rafStatus != null) {
            list.add(rafStatus);
            rafStatus = null;
        }
    }

    @Override
    public CompletableFuture<SleParameter> getParameter(ParameterName paraName) {
        RacfServiceUserHandler _rsuh = rsuh;
        if (_rsuh == null || !_rsuh.isConnected()) {
            throw new SleException("link not connected");
        }
        return _rsuh.getParameter(paraName.id());
    }

    @Override
    public boolean isParameterDataLinkImplemented() {
        return privateAnnotationParameter != null;
    }

    @Override
    public void setParameterSink(ParameterSink parameterSink) {
        this.parameterSink = parameterSink;
    }

    /**
     * Monitors one connection. Once the connection is no longer the current one (the link has been disabled or the
     * connection lost), its callbacks are ignored, except for logging: otherwise a closing connection could shut
     * down or change the state of a newer one.
     */
    class MyMonitor implements RacfSleMonitor {
        final RacfServiceUserHandler suh;

        private ProductionStatus prodStatus;
        private LockStatus subcarrierLockStatus;
        private LockStatus symbolSyncLockStatus;
        private LockStatus carrierLockStatus;

        MyMonitor(RacfServiceUserHandler suh) {
            this.suh = suh;
        }

        boolean isCurrent() {
            return rsuh == suh;
        }

        @Override
        public void connected() {
            eventProducer.sendInfo("SLE connected");
        }

        @Override
        public void disconnected() {
            eventProducer.sendInfo("SLE disconnected");
            if (!clearIfCurrent(suh)) {
                return;
            }
            endpointHandler.reset();
            sleState = org.yamcs.jsle.State.UNBOUND;

            if (isRunningAndEnabled() && sconf.reconnectionIntervalSec >= 0) {
                getEventLoop().schedule(() -> connectAndBind(requestedState == org.yamcs.jsle.State.ACTIVE),
                        sconf.reconnectionIntervalSec, TimeUnit.SECONDS);
            }
        }

        @Override
        public void stateChanged(org.yamcs.jsle.State newState) {
            eventProducer.sendInfo("SLE state changed to " + newState);
            if (!isCurrent()) {
                return;
            }
            sleState = newState;
            if (stopAction != null && startAction != null) {
                if (!isEffectivelyDisabled()) {
                    switch (sleState) {
                    case UNBOUND:
                    case READY:
                        startAction.setEnabled(true);
                        stopAction.setEnabled(false);
                        break;
                    case BINDING:
                    case STARTING:
                    case STOPPING:
                    case UNBINDING:
                        startAction.setEnabled(false);
                        stopAction.setEnabled(false);
                        break;
                    case ACTIVE:
                        startAction.setEnabled(false);
                        stopAction.setEnabled(true);
                        break;
                    }
                } else {
                    startAction.setEnabled(false);
                    stopAction.setEnabled(false);
                }
            }
        }

        @Override
        public void exceptionCaught(Throwable t) {
            log.warn("SLE exception caught", t);
            eventProducer.sendInfo("SLE exception caught: " + t.getMessage());
        }

        @Override
        public void onStatusReport(RacfStatusReport rafStatusReport) {
            if (!isCurrent()) {
                return;
            }
            prodStatus = rafStatusReport.getProductionStatus();
            carrierLockStatus = rafStatusReport.getCarrierLockStatus();
            subcarrierLockStatus = rafStatusReport.getSubcarrierLockStatus();
            symbolSyncLockStatus = rafStatusReport.getSymbolSyncLockStatus();

            AggregateValue tmp = new AggregateValue(rafStatusMembers);

            tmp.setMemberValue("productionStatus",
                    ValueUtility.getEnumeratedValue(prodStatus.ordinal(), prodStatus.name()));
            tmp.setMemberValue("carrierLockStatus", ValueUtility.getEnumeratedValue(
                    carrierLockStatus.ordinal(), carrierLockStatus.name()));
            tmp.setMemberValue("subcarrierLockStatus",
                    ValueUtility.getEnumeratedValue(subcarrierLockStatus.ordinal(), subcarrierLockStatus.name()));
            tmp.setMemberValue("symbolSyncLockStatus",
                    ValueUtility.getEnumeratedValue(symbolSyncLockStatus.ordinal(), symbolSyncLockStatus.name()));

            tmp.setMemberValue("deliveredFrameNumber",
                    ValueUtility.getSint32Value(rafStatusReport.getDeliveredFrameNumber()));
            tmp.setMemberValue("errorFreeFrameNumber",
                    ValueUtility.getSint32Value(rafStatusReport.getErrorFreeFrameNumber()));

            rafStatus = SystemParametersService.getPV(sp_racfStatus, getCurrentTime(), tmp);
        }
    }

    static Instant toInstant(CcsdsTime ccsdsTime) {
        long picosInDay = ccsdsTime.getPicosecInDay();

        long millis = (ccsdsTime.getNumDays() - CcsdsTime.NUM_DAYS_1958_1970) * (long) CcsdsTime.MS_IN_DAY
                + picosInDay / 1_000_000_000l;
        int picos = (int) (picosInDay % 1_000_000_000);
        return TimeEncoding.fromUnixPicos(millis, picos);
    }
}

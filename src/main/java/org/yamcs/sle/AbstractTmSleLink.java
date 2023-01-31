package org.yamcs.sle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
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
import org.yamcs.tctm.Link;
import org.yamcs.tctm.LinkAction;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.time.Instant;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.EnumeratedParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.SystemParameter;
import org.yamcs.xtce.util.AggregateMemberNames;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

public abstract class AbstractTmSleLink extends AbstractTmFrameLink implements FrameConsumer, SleLink {
    String packetPreprocessorClassName;
    Object packetPreprocessorArgs;
    RacfServiceUserHandler rsuh;

    RacfSleMonitor sleMonitor = new MyMonitor();
    SleConfig sconf;
    DeliveryMode deliveryMode;

    String service;

    org.yamcs.jsle.State sleState = org.yamcs.jsle.State.UNBOUND;

    org.yamcs.jsle.State requestedState = org.yamcs.jsle.State.UNBOUND;

    private volatile ParameterValue rafStatus;

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
        public JsonObject execute(Link link, JsonObject jsonObject) {
            if (requestedState == org.yamcs.jsle.State.UNBOUND) {
                connectAndBind(true);
            } else {
                sleStart();
            }
            return null;
        }
    };
    LinkAction stopAction = new LinkAction("stop", "Stop SLE") {
        @Override
        public JsonObject execute(Link link, JsonObject jsonObject) {
            sleStop();
            return null;
        }
    };

    /**
     * Creates a new UDP Frame Data Link
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

    }

    protected synchronized void connectAndBind(boolean startSle) {
        if (!isRunningAndEnabled()) {
            return;
        }
        requestedState = org.yamcs.jsle.State.READY;

        eventProducer.sendInfo("Connecting to SLE " + service + " service " + sconf.host + ":" + sconf.port
                + " as user " + sconf.auth.getMyUsername());
        if (gvcid == null) {
            rsuh = new RafServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
            ((RafServiceUserHandler) rsuh).setRequestedFrameQuality(frameQuality);
        } else {
            rsuh = new RcfServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
        }
        rsuh.setVersionNumber(sconf.versionNumber);
        rsuh.setAuthLevel(sconf.authLevel);
        rsuh.setReturnTimeoutSec(sconf.returnTimeoutSec);
        rsuh.addMonitor(sleMonitor);

        NioEventLoopGroup workerGroup = getEventLoop();
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(sconf.tmlMaxLength, 4, 4));
                ch.pipeline().addLast(new Isp1Handler(true, sconf.hbSettings));
                ch.pipeline().addLast(rsuh);
            }
        });
        b.connect(sconf.host, sconf.port).addListener(f -> {
            if (!f.isSuccess()) {
                eventProducer.sendWarning("Failed to connect to the SLE provider: " + f.cause().getMessage());
                rsuh = null;
                if (sconf.reconnectionIntervalSec >= 0) {
                    workerGroup.schedule(() -> connectAndBind(startSle), sconf.reconnectionIntervalSec, TimeUnit.SECONDS);
                }
            } else {
                sleBind(startSle);
            }
        });
    }

    private void sleBind(boolean startSle) {
        requestedState = org.yamcs.jsle.State.READY;

        rsuh.bind().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to bind: " + t.getMessage());
                return null;
            }
            if (startSle) {
                sleStart();
            }
            return null;
        });
    }

    abstract void sleStart();

    void sleStop() {
        requestedState = org.yamcs.jsle.State.READY;
        log.debug("Stopping SLE service");

        rsuh.stop().handle((v, t) -> {
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
     * @return Status.OK or Status.UNAVAIL
     */
    @Override
    protected Status connectionStatus() {
        return sleState != org.yamcs.jsle.State.UNBOUND && sleState == requestedState ? Status.OK : Status.UNAVAIL;
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

        if (log.isTraceEnabled()) {
            log.trace("Received frame length: {}, data: {}", data.length, StringConverter.arrayToHexString(data));
        }
        try {
            if (length < frameHandler.getMinFrameSize()) {
                eventProducer.sendWarning("Error processing frame: size " + length
                        + " shorter than minimum allowed " + frameHandler.getMinFrameSize());
            } else if (length > frameHandler.getMaxFrameSize()) {
                eventProducer.sendWarning("Error processing frame: size " + length + " longer than maximum allowed "
                        + frameHandler.getMaxFrameSize());
            } else {
                frameCount.incrementAndGet();
                Instant ertime = toInstant(ert);

                frameHandler.handleFrame(ertime, data, 0, length);
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

    class MyMonitor implements RacfSleMonitor {

        private ProductionStatus prodStatus;
        private LockStatus subcarrierLockStatus;
        private LockStatus symbolSyncLockStatus;
        private LockStatus carrierLockStatus;

        @Override
        public void connected() {
            eventProducer.sendInfo("SLE connected");
        }

        @Override
        public void disconnected() {
            eventProducer.sendInfo("SLE disconnected");
            if (rsuh != null) {
                rsuh.shutdown();
                rsuh = null;
            }

            if (isRunningAndEnabled() && sconf.reconnectionIntervalSec >= 0) {
                getEventLoop().schedule(() -> connectAndBind(requestedState == org.yamcs.jsle.State.ACTIVE), sconf.reconnectionIntervalSec, TimeUnit.SECONDS);
            }
        }

        @Override
        public void stateChanged(org.yamcs.jsle.State newState) {
            eventProducer.sendInfo("SLE state changed to " + newState);
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

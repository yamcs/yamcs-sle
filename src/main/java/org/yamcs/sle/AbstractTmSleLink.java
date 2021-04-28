package org.yamcs.sle;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.sle.Constants.DeliveryMode;
import org.yamcs.sle.Constants.FrameQuality;
import org.yamcs.sle.Constants.LockStatus;
import org.yamcs.sle.Constants.ProductionStatus;
import org.yamcs.sle.user.FrameConsumer;
import org.yamcs.sle.user.RacfServiceUserHandler;
import org.yamcs.sle.user.RacfStatusReport;
import org.yamcs.sle.user.RafServiceUserHandler;
import org.yamcs.sle.user.RcfServiceUserHandler;
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

public abstract class AbstractTmSleLink extends AbstractTmFrameLink implements FrameConsumer {
    String packetPreprocessorClassName;
    Object packetPreprocessorArgs;
    RacfServiceUserHandler rsuh;

    RacfSleMonitor sleMonitor = new MyMonitor();
    SleConfig sconf;
    DeliveryMode deliveryMode;
    String service;

    // how soon should reconnect in case the connection to the SLE provider is lost
    // if negative, do not reconnect
    int reconnectionIntervalSec;

    private org.yamcs.sle.State sleState = org.yamcs.sle.State.UNBOUND;
    private volatile ParameterValue rafStatus;

    private SystemParameter sp_sleState, sp_racfStatus;
    final static AggregateMemberNames rafStatusMembers = AggregateMemberNames.get(new String[] { "productionStatus",
            "carrierLockStatus", "subcarrierLockStatus", "symbolSyncLockStatus", "deliveredFrameNumber",
            "errorFreeFrameNumber" });

    // if null-> RAF, otherwise RCF
    GVCID gvcid = null;

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
        service = config.getString("service", "RAF");
        gvcid = null;
        if ("RCF".equals(service)) {
            int tfVersion = config.getInt("rcfTfVersion", frameHandler.getFrameType().getVersion());
            int spacecraftId = config.getInt("rcfSpacecraftId", frameHandler.getSpacecraftId());
            int vcId = config.getInt("rcfVcId", -1);
            gvcid = new GVCID(tfVersion, spacecraftId, vcId);
        } else if (!"RAF".equals(service)) {
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

    protected synchronized void connect() {
        if (!isRunningAndEnabled()) {
            return;
        }
        eventProducer.sendInfo("Connecting to SLE " + service + " service " + sconf.host + ":" + sconf.port
                + " as user " + sconf.auth.getMyUsername());
        if (gvcid == null) {
            rsuh = new RafServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
        } else {
            rsuh = new RcfServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
        }
        rsuh.setVersionNumber(sconf.versionNumber);
        rsuh.setAuthLevel(sconf.authLevel);
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
                if (reconnectionIntervalSec >= 0) {
                    workerGroup.schedule(() -> connect(), reconnectionIntervalSec, TimeUnit.SECONDS);
                }
            } else {
                sleBind();
            }
        });
    }

    private void sleBind() {
        rsuh.bind().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to bind: " + t.getMessage());
                return null;
            }
            sleStart();
            return null;
        });
    }

    abstract void sleStart();

    @Override
    protected Status connectionStatus() {
        return (rsuh != null && rsuh.getState() == org.yamcs.sle.State.ACTIVE) ? Status.OK : Status.UNAVAIL;
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
        sp_sleState = sps.createEnumeratedSystemParameter(linkName + "/sleState", org.yamcs.sle.State.class,
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

            if (isRunningAndEnabled() && reconnectionIntervalSec >= 0) {
                getEventLoop().schedule(() -> connect(), reconnectionIntervalSec, TimeUnit.SECONDS);
            }
        }

        @Override
        public void stateChanged(org.yamcs.sle.State newState) {
            eventProducer.sendInfo("SLE state changed to " + newState);
            sleState = newState;
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

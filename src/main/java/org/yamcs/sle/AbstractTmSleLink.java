package org.yamcs.sle;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersCollector;
import org.yamcs.sle.FrameConsumer;
import org.yamcs.sle.Isp1Handler;
import org.yamcs.sle.RafServiceUserHandler;
import org.yamcs.sle.RafSleMonitor;
import org.yamcs.sle.CcsdsTime;
import org.yamcs.sle.Constants.DeliveryMode;
import org.yamcs.sle.Constants.LockStatus;
import org.yamcs.sle.Constants.RafProductionStatus;
import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.ccsds.AbstractTmFrameLink;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.util.AggregateMemberNames;

import ccsds.sle.transfer.service.common.types.Time;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafStatusReportInvocation;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafTransferDataInvocation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 *
 */
public abstract class AbstractTmSleLink extends AbstractTmFrameLink implements FrameConsumer {
    String packetPreprocessorClassName;
    Object packetPreprocessorArgs;
    RafServiceUserHandler rsuh;

    RafSleMonitor sleMonitor = new MyMonitor();
    final SleConfig sconf;
    final DeliveryMode deliveryMode;

    // how soon should reconnect in case the connection to the SLE provider is lost
    // if negative, do not reconnect
    int reconnectionIntervalSec;

    private org.yamcs.sle.AbstractServiceUserHandler.State sleState = org.yamcs.sle.AbstractServiceUserHandler.State.UNBOUND;
    private volatile ParameterValue rafStatus;
    
    private String sp_sleState_id, sp_rafStatus_id;
    final static AggregateMemberNames rafStatusMembers = AggregateMemberNames.get(new String[] { "productionStatus",
            "carrierLockStatus", "subcarrierLockStatus", "symbolSyncLockStatus", "deliveredFrameNumber", "errorFreeFrameNumber"});

    /**
     * Creates a new UDP Frame Data Link
     * @param deliveryMode 
     * 
     * @throws ConfigurationException
     *             if port is not defined in the configuration
     */
    public AbstractTmSleLink(String instance, String name, YConfiguration config, DeliveryMode deliveryMode) throws ConfigurationException {
        super(instance, name, config);
        this.deliveryMode = deliveryMode;
        YConfiguration slec = YConfiguration.getConfiguration("sle").getConfig("Providers")
                .getConfig(config.getString("sleProvider"));

        String type;
        switch (deliveryMode) {
        case rtnCompleteOnline:
            type = "raf-onlc";
            break;
        case rtnTimelyOnline:
            type = "raf-onlt";
            break;
        case rtnOffline:
            type = "raf-offl";
            break;
         default:
             throw new ConfigurationException("Invalid delivery mode "+deliveryMode+" for this data link");

        }
        this.sconf = new SleConfig(slec, type);
    }

    protected synchronized void connect() {
        log.debug("Connecting to SLE RAF service {}:{} as user {}", sconf.host, sconf.port, sconf.auth.getMyUsername());
        rsuh = new RafServiceUserHandler(sconf.auth, sconf.attr, deliveryMode, this);
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
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(8192, 4, 4));
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

  
    private long getTime(Time t) {
        CcsdsTime ct = CcsdsTime.fromSle(t);
        return TimeEncoding.fromUnixMillisec(ct.toJavaMillisec());
    }

    @Override
    protected Status connectionStatus() {
        return rsuh != null && rsuh.isConnected() ? Status.OK : Status.UNAVAIL;
    }

    @Override
    public void acceptFrame(RafTransferDataInvocation rtdi) {

        if (isDisabled()) {
            log.debug("Ignoring frame received while disabled");
            return;
        }

        byte[] data = rtdi.getData().value;
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
                frameCount++;
                long ertime = getTime(rtdi.getEarthReceiveTime());

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
    public void onProductionStatusChange(RafProductionStatus productionStatusChange) {
        eventProducer.sendInfo("SLE production satus changed to " + productionStatusChange);

    }

    @Override
    public void onLossFrameSync(CcsdsTime time, LockStatus carrier, LockStatus subcarrier, LockStatus symbolSync) {
        // TODO make some parameters out of this
        eventProducer.sendInfo("SLE loss frame sync time: " + time + " carrier: " + carrier + " subcarrier: "
                + subcarrier + " symbolSync: " + symbolSync);
    }

    @Override
    protected void setupSystemParameters() {
        super.setupSystemParameters();
        if (sysParamCollector != null) {
            sp_sleState_id = sysParamCollector.getNamespace() + "/" + linkName + "/sleState";
            sp_rafStatus_id = sysParamCollector.getNamespace() + "/" + linkName + "/rafStatus";
        }
    }


    @Override
    protected void collectSystemParameters(long time, List<ParameterValue> list) {
        super.collectSystemParameters(time, list);
        list.add(SystemParametersCollector.getPV(sp_sleState_id, time, sleState.name()));
        if (rafStatus != null) {
            list.add(rafStatus);
            rafStatus = null;
        }
    }

    class MyMonitor implements RafSleMonitor {

        private RafProductionStatus prodStatus;
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
        }

        @Override
        public void stateChanged(org.yamcs.sle.AbstractServiceUserHandler.State newState) {
            eventProducer.sendInfo("SLE state changed to " + newState);
            sleState = newState;
        }

        @Override
        public void exceptionCaught(Throwable t) {
            log.warn("SLE exception caught", t);
            eventProducer.sendInfo("SLE exception caught: " + t.getMessage());
        }

        @Override
        public void onRafStatusReport(RafStatusReportInvocation rafStatusReport) {
            prodStatus = RafProductionStatus.byId(rafStatusReport.getProductionStatus().intValue());
            carrierLockStatus = LockStatus.byId(rafStatusReport.getCarrierLockStatus().intValue());
            subcarrierLockStatus = LockStatus.byId(rafStatusReport.getSubcarrierLockStatus().intValue());
            symbolSyncLockStatus = LockStatus.byId(rafStatusReport.getSymbolSyncLockStatus().intValue());
            
            AggregateValue tmp = new AggregateValue(rafStatusMembers);

            tmp.setMemberValue("productionStatus", ValueUtility.getStringValue(prodStatus.name()));
            tmp.setMemberValue("carrierLockStatus", ValueUtility.getStringValue(carrierLockStatus.name()));
            tmp.setMemberValue("subcarrierLockStatus", ValueUtility.getStringValue(subcarrierLockStatus.name()));
            tmp.setMemberValue("symbolSyncLockStatus", ValueUtility.getStringValue(symbolSyncLockStatus.name()));
            
            tmp.setMemberValue("deliveredFrameNumber",
                    ValueUtility.getSint32Value(rafStatusReport.getDeliveredFrameNumber().intValue()));
            tmp.setMemberValue("errorFreeFrameNumber",
                    ValueUtility.getSint32Value(rafStatusReport.getErrorFreeFrameNumber().intValue()));

            rafStatus = SystemParametersCollector.getPV(sp_rafStatus_id, getCurrentTime(), tmp);
        }
    }
}

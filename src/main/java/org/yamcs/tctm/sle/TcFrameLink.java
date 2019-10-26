package org.yamcs.tctm.sle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.api.EventProducer;
import org.yamcs.api.EventProducerFactory;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersCollector;
import org.yamcs.sle.Isp1Handler;
import org.yamcs.sle.CcsdsTime;
import org.yamcs.sle.CltuServiceUserHandler;
import org.yamcs.sle.CltuSleMonitor;
import org.yamcs.sle.Constants.CltuProductionStatus;
import org.yamcs.sle.Constants.UplinkStatus;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.TcFrameFactory;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.tctm.ccsds.DownlinkManagedParameters.FrameErrorCorrection;
import org.yamcs.tctm.ccsds.error.BchCltuGenerator;
import org.yamcs.tctm.ccsds.error.CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc256CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc64CltuGenerator;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.util.AggregateMemberNames;

import ccsds.sle.transfer.service.cltu.outgoing.pdus.CltuAsyncNotifyInvocation;
import ccsds.sle.transfer.service.cltu.outgoing.pdus.CltuStatusReportInvocation;
import ccsds.sle.transfer.service.cltu.structures.CltuLastOk.CltuOk;
import ccsds.sle.transfer.service.cltu.structures.CltuNotification;
import ccsds.sle.transfer.service.cltu.structures.DiagnosticCltuTransferData;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * SendsTC frames embedded in CLTU (CCSDS 231.0-B-3) via FCLTU SLE service.
 * 
 * @author nm
 *
 */
public class TcFrameLink extends AbstractTcFrameLink {
    FrameErrorCorrection errorCorrection;
    TcFrameFactory tcFrameFactory;
    CltuGenerator cltuGenerator;
    SleConfig sconf;

    private Log log;
    CltuServiceUserHandler csuh;
    CltuSleMonitor sleMonitor;
    EventProducer eventProducer;
    Map<Integer, TcTransferFrame> pendingFrames = new ConcurrentHashMap<>();
    public final static String CMDHISTORY_SLE_REQ_KEY = "SLE_REQ";
    public final static String CMDHISTORY_SLE_RADIATED_KEY = "SLE_RADIATED";

    CltuProductionStatus prodStatus = CltuProductionStatus.configured;
    UplinkStatus uplinkStatus;

    private Semaphore uplinkReadySemaphore = new Semaphore(0);

    // if a command is received and the uplink is not available, wait this number of milliseconds for the uplink to
    // become available
    // if 0 or negative, then drop the command immediately
    long waitForUplinkMsec;

    // maximum number of pending frames in the SLE provider. If this number is reached we start rejecting new frames
    // but only after waiting waitForUplinkMsec before each frame
    int maxPendingFrames;

    // how soon should reconnect in case the connection to the SLE provider is lost
    // if negative, do not reconnect
    int reconnectionIntervalSec;

    org.yamcs.sle.AbstractServiceUserHandler.State sleState;

    private String sv_sleState_id, sp_cltuStatus_id, sp_numPendingFrames_id;
    final static AggregateMemberNames cltuStatusMembers = AggregateMemberNames.get(new String[] { "productionStatus",
            "uplinkStatus", "numCltuReceived", "numCltuProcessed", "numCltuRadiated", "cltuBufferAvailable" });

    private volatile ParameterValue cltuStatus;

    public TcFrameLink(String yamcsInstance, String name, YConfiguration config) {
        super(yamcsInstance, name, config);
        log = new Log(getClass(), yamcsInstance);
        log.setContext(name);

        maxPendingFrames = config.getInt("maxPendingFrames", 20);
        waitForUplinkMsec = config.getInt("waitForUplinkMsec", 5000);
        reconnectionIntervalSec = config.getInt("reconnectionIntervalSec", 30);

        YConfiguration slec = YConfiguration.getConfiguration("sle").getConfig("Providers")
                .getConfig(config.getString("sleProvider"));
        sconf = new SleConfig(slec, "cltu");

        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, "SLE[" + name + "]", 10000);

        String encoding = config.getString("encoding");

        if ("BCH".equals(encoding)) {
            cltuGenerator = new BchCltuGenerator(config.getBoolean("randomizeCltu", false));
        } else if ("LDPC64".equals(encoding)) {
            cltuGenerator = new Ldpc64CltuGenerator(config.getBoolean("ldpc64Tail", false));
        } else if ("LDPC256".equals(encoding)) {
            cltuGenerator = new Ldpc256CltuGenerator();
        } else {
            throw new ConfigurationException(
                    "Invalid value '" + encoding + " for encoding. Valid values are BCH, LDPC64 or LDPC256");
        }
        sleMonitor = new MyMonitor();
    }

    private synchronized void connect() {
        log.info("Connecting to SLE FCLTU service {}:{} as user {}", sconf.host, sconf.port,
                sconf.auth.getMyUsername());
        csuh = new CltuServiceUserHandler(sconf.auth, sconf.attr);
        csuh.setVersionNumber(sconf.versionNumber);
        csuh.setAuthLevel(sconf.authLevel);
        csuh.addMonitor(sleMonitor);

        NioEventLoopGroup workerGroup = EventLoopResource.getEventLoop();
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(8192, 4, 4));
                ch.pipeline().addLast(new Isp1Handler(true, sconf.hbSettings));
                ch.pipeline().addLast(csuh);
            }
        });
        b.connect(sconf.host, sconf.port).addListener(f -> {
            if (!f.isSuccess()) {
                eventProducer.sendWarning("Failed to connect to the SLE provider: " + f.cause().getMessage());
                csuh = null;
                if (!disabled && reconnectionIntervalSec >= 0) {
                    workerGroup.schedule(() -> connect(), reconnectionIntervalSec, TimeUnit.SECONDS);
                }
            } else {
                sleBind();
            }
        });
    }

    @Override
    public void triggerShutdown() {
        if (csuh != null) {
            csuh.shutdown();
        }
        multiplexer.quit();
    }

    @Override
    protected void run() throws Exception {
        while (isRunning()) {
            if (csuh == null) {
                connect();
                continue;
            }
            TcTransferFrame tf = multiplexer.getFrame();
            if (tf != null) {
                log.trace("New TC frame: {}", tf);

                byte[] data = tf.getData();
                if (cltuGenerator != null) {
                    data = cltuGenerator.makeCltu(data);
                }

                if (!isUplinkPossible() && waitForUplinkMsec > 0) {
                    waitForUplink(waitForUplinkMsec);
                }

                if (!isUplinkPossible()) {
                    log.debug("TC frame {} dropped because uplink is not availalbe", tf);
                    if (tf.isBypass()) {
                        failBypassFrame(tf, "SLE uplink not available");
                    }
                    continue;
                }

                int id = csuh.transferCltu(data);
                pendingFrames.put(id, tf);

                for (PreparedCommand pc : tf.getCommands()) {
                    commandHistoryPublisher.publishAck(pc.getCommandId(), CMDHISTORY_SLE_REQ_KEY,
                            TimeEncoding.getWallclockTime(), AckStatus.OK);
                }

            }

        }
    }

    @Override
    public void enable() {
        disabled = false;
        connect();
    }

    @Override
    public void disable() {
        disabled = true;
        if (csuh != null) {
            csuh.shutdown();
            csuh = null;
        }
    }

    private boolean isUplinkPossible() {
        return (csuh != null) && csuh.isConnected() && pendingFrames.size() < maxPendingFrames
                && sleState == org.yamcs.sle.AbstractServiceUserHandler.State.ACTIVE
                && prodStatus == CltuProductionStatus.operational;
    }

    private void waitForUplink(long waitMsec) {
        if (waitMsec <= 0) {
            return;
        }
        long t0 = System.currentTimeMillis();
        long left = waitMsec;
        while (left > 0) {
            left = waitMsec - (System.currentTimeMillis() - t0);
            try {
                uplinkReadySemaphore.tryAcquire(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (isUplinkPossible()) {
                break;
            }

            left = waitMsec - (System.currentTimeMillis() - t0);
        }
    }

    private void sleBind() {
        csuh.bind().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to bind: " + t.getMessage());
                return null;
            }
            log.debug("BIND successfull, starting the service");
            sleStart();
            return null;
        });
    }

    private void sleStart() {
        csuh.start().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to start: " + t.getMessage());
                return null;
            }
            log.debug("Successfully started the service");
            csuh.schedulePeriodicStatusReport(10);
            return null;
        });
    }

    private void onCltuRadiated(CltuOk cltuOk) {
        int cltuId = cltuOk.getCltuIdentification().value.intValue();
        CcsdsTime time = CcsdsTime.fromSle(cltuOk.getRadiationStopTime());
        TcTransferFrame tf = pendingFrames.remove(cltuId);
        if (tf == null) {
            log.warn("Received cltu-radiated event for unknown cltuId {}", cltuId);
            return;
        }

        if (tf.isBypass()) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(),
                        CommandHistoryPublisher.ACK_SENT_CNAME_PREFIX,
                        TimeEncoding.getWallclockTime(), AckStatus.OK);
            }
        }

        for (PreparedCommand pc : tf.getCommands()) {
            commandHistoryPublisher.publish(pc.getCommandId(), CMDHISTORY_SLE_RADIATED_KEY, time.toString());
        }

        uplinkReadySemaphore.release();
    }

    private void failBypassFrame(TcTransferFrame tf, String reason) {
        for (PreparedCommand pc : tf.getCommands()) {
            commandHistoryPublisher.publishAck(pc.getCommandId(),
                    CommandHistoryPublisher.ACK_SENT_CNAME_PREFIX,
                    TimeEncoding.getWallclockTime(), AckStatus.NOK, "SLE disconnected");

            commandHistoryPublisher.commandFailed(pc.getCommandId(), timeService.getMissionTime(), "SLE disconnected");
        }
    }

    protected void setupSysVariables() {
        super.setupSysVariables();
        if (sysParamCollector != null) {
            sv_sleState_id = sysParamCollector.getNamespace() + "/" + name + "/sleState";
            sp_numPendingFrames_id = sysParamCollector.getNamespace() + "/" + name + "/numPendingFrames";
            sp_cltuStatus_id = sysParamCollector.getNamespace() + "/" + name + "/cltuStatus";
        }
    }

    @Override
    public List<ParameterValue> getSystemParameters() {
        List<ParameterValue> l = super.getSystemParameters();
        long time = l.get(0).getGenerationTime();
        l.add(SystemParametersCollector.getPV(sv_sleState_id, time, sleState.name()));
        l.add(SystemParametersCollector.getPV(sp_numPendingFrames_id, time, pendingFrames.size()));
        if (cltuStatus != null) {
            l.add(cltuStatus);
            cltuStatus = null;
        }
        return l;
    }

    @Override
    public Status getLinkStatus() {
        if (disabled) {
            return Status.DISABLED;
        }
        if (state() == State.FAILED) {
            return Status.FAILED;
        }

        if (isUplinkPossible()) {
            return Status.OK;
        } else {
            return Status.UNAVAIL;
        }
    }

    class MyMonitor implements CltuSleMonitor {
        @Override
        public void connected() {
            System.out.println("sending sle connected info event with "+eventProducer.getClass());
            eventProducer.sendInfo("SLE connected");
        }

        @Override
        public void disconnected() {
            eventProducer.sendInfo("SLE disconnected");
            if (csuh != null) {
                csuh.shutdown();
                csuh = null;
            }

            for (TcTransferFrame tf : pendingFrames.values()) {
                if (tf.isBypass()) {
                    failBypassFrame(tf, "SLE disconnected");
                }
            }
        }

        @Override
        public void stateChanged(org.yamcs.sle.AbstractServiceUserHandler.State newState) {
            eventProducer.sendInfo("SLE state changed to " + newState);

        }

        @Override
        public void exceptionCaught(Throwable t) {
            log.warn("SLE exception caught", t);
            eventProducer.sendWarning("SLE exception caught: " + t.getMessage());
        }

        @Override
        public void onCltuStatusReport(CltuStatusReportInvocation cltuStatusReport) {
            prodStatus = CltuProductionStatus.byId(cltuStatusReport.getCltuProductionStatus().intValue());
            uplinkStatus = UplinkStatus.byId(cltuStatusReport.getUplinkStatus().intValue());
            AggregateValue tmp = new AggregateValue(cltuStatusMembers);

            tmp.setMemberValue("productionStatus", ValueUtility.getStringValue(prodStatus.name()));
            tmp.setMemberValue("uplinkStatus", ValueUtility.getStringValue(uplinkStatus.name()));
            tmp.setMemberValue("numCltuReceived",
                    ValueUtility.getSint32Value(cltuStatusReport.getNumberOfCltusReceived().intValue()));
            tmp.setMemberValue("numCltuProcessed",
                    ValueUtility.getSint32Value(cltuStatusReport.getNumberOfCltusProcessed().intValue()));
            tmp.setMemberValue("numCltuRadiated",
                    ValueUtility.getSint32Value(cltuStatusReport.getNumberOfCltusRadiated().intValue()));
            tmp.setMemberValue("cltuBufferAvailable",
                    ValueUtility.getUint64Value(cltuStatusReport.getCltuBufferAvailable().longValue()));

            cltuStatus = SystemParametersCollector.getPV(sp_cltuStatus_id, getCurrentTime(), tmp);
        }

        @Override
        public void onAsyncNotify(CltuAsyncNotifyInvocation cltuAsyncNotifyInvocation) {
            if (log.isTraceEnabled()) {
                log.trace("received cltuAsyncNotifyInvocation:{} ", cltuAsyncNotifyInvocation);
            }
            CltuNotification cn = cltuAsyncNotifyInvocation.getCltuNotification();
            prodStatus = CltuProductionStatus.byId(cltuAsyncNotifyInvocation.getProductionStatus().intValue());

            if (cn.getCltuRadiated() != null) {
                onCltuRadiated(cltuAsyncNotifyInvocation.getCltuLastOk().getCltuOk());
            } else if (cn.getProductionInterrupted() != null) {
                eventProducer.sendInfo("CLTU Production interrupted");
            } else if (cn.getProductionHalted() != null) {
                eventProducer.sendInfo("CLTU Production halted");
            } else if (cn.getProductionOperational() != null) {
                eventProducer.sendInfo("CLTU Production operational");
                uplinkReadySemaphore.release();
            } else if (cn.getBufferEmpty() != null) {
                log.debug("CLTU buffer empty");
            } else {
                log.warn("Unexpected CltuNotification received: {}", cltuAsyncNotifyInvocation);
            }
        }

        @Override
        public void onPositiveTransfer(int cltuId) {
        }

        @Override
        public void onNegativeTransfer(int cltuId, DiagnosticCltuTransferData negativeResult) {
            TcTransferFrame tf = pendingFrames.remove(cltuId);
            if (tf.isBypass()) {
                failBypassFrame(tf, negativeResult.toString());
            }
            uplinkReadySemaphore.release();
        }
    }

}

package org.yamcs.tctm.sle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.api.EventProducer;
import org.yamcs.api.EventProducerFactory;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.sle.Isp1Handler;
import org.yamcs.sle.CltuServiceUserHandler;
import org.yamcs.sle.CltuSleMonitor;
import org.yamcs.sle.Constants.CltuProductionStatus;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.TcFrameFactory;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.tctm.ccsds.DownlinkManagedParameters.FrameErrorCorrection;
import org.yamcs.tctm.ccsds.error.BchCltuGenerator;
import org.yamcs.tctm.ccsds.error.CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc256CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc64CltuGenerator;
import org.yamcs.utils.TimeEncoding;

import ccsds.sle.transfer.service.cltu.outgoing.pdus.CltuAsyncNotifyInvocation;
import ccsds.sle.transfer.service.cltu.outgoing.pdus.CltuStatusReportInvocation;
import ccsds.sle.transfer.service.cltu.structures.CltuLastProcessed;
import ccsds.sle.transfer.service.cltu.structures.CltuNotification;
import ccsds.sle.transfer.service.cltu.structures.DiagnosticCltuTransferData;
import ccsds.sle.transfer.service.cltu.structures.ProductionStatus;
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
    Log log;
    CltuServiceUserHandler csuh;
    CltuSleMonitor sleMonitor;
    EventProducer eventProducer;
    Map<Integer, TcTransferFrame> pendingFrames = new ConcurrentHashMap<>();
    public final static String CMDHISTORY_SLE_REQ_KEY = "SLE_REQ";

    CltuProductionStatus prodStatus = CltuProductionStatus.configured;
    private Semaphore uplinkReadySemaphore = new Semaphore(0);

    // if a command is received and the uplink is not available, wait this number of milliseconds for the uplink to
    // become available
    // if 0 or negative, then drop the command immediately
    long waitForUplinkMsec;

    // maximum number of pending frames in the SLE provider. If this number is reached we start rejecting new frames
    // but only after waiting waitForUplinkMsec before each frame
    int maxPendingFrames;

    org.yamcs.sle.AbstractServiceUserHandler.State sleState;

    public TcFrameLink(String yamcsInstance, String name, YConfiguration config) {
        super(yamcsInstance, name, config);
        log = new Log(getClass(), yamcsInstance);

        maxPendingFrames = config.getInt("maxPendingFrames", 20);
        waitForUplinkMsec = config.getInt("waitForUplinkMsec", 5000);

        sconf = new SleConfig(config);
        String cltu = config.getString("cltu", null);
        eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, "SLE[" + name + "]", 10000);

        if (cltu != null) {
            if ("BCH".equals(cltu)) {
                cltuGenerator = new BchCltuGenerator(config.getBoolean("randomizeCltu", false));
            } else if ("LDPC64".equals(cltu)) {
                cltuGenerator = new Ldpc64CltuGenerator(config.getBoolean("ldpc64Tail", false));
            } else if ("LDPC256".equals(cltu)) {
                cltuGenerator = new Ldpc256CltuGenerator();
            } else {
                throw new ConfigurationException(
                        "Invalid value '" + cltu + " for cltu. Valid values are BCH, LDPC64 or LDPC256");
            }
        }
        sleMonitor = new MyMonitor();
    }

    private synchronized void connect() {
        log.debug("Connecting to SLE FCLTU service {}:{} as user {}", sconf.host, sconf.port,
                sconf.auth.getMyUsername());
        csuh = new CltuServiceUserHandler(sconf.auth, sconf.responderPortId, sconf.initiatorId,
                sconf.serviceInstanceNumber);
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
                ch.pipeline().addLast(new Isp1Handler(true));
                ch.pipeline().addLast(csuh);
            }
        });
        b.connect(sconf.host, sconf.port).addListener(f -> {
            if (!f.isSuccess()) {
                eventProducer.sendWarning("Failed to connect to the SLE provider: " + f.cause().getMessage());
                csuh = null;
            } else {
                sleBind();
            }
        });
    }

    @Override
    protected void startUp() throws Exception {
        super.startUp();
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
                byte[] data = tf.getData();
                if (cltuGenerator != null) {
                    data = cltuGenerator.makeCltu(data);
                }
                waitForUplink(waitForUplinkMsec);

                if (!isUplinkPossible()) {
                    if (tf.isBypass()) {
                        failBypassFrame(tf, "SLE uplink not available");
                    }
                    continue;
                }

                int id = csuh.transferCltu(data);
                pendingFrames.put(id, tf);

                for (PreparedCommand pc : tf.getCommands()) {
                    commandHistoryPublisher.publishWithTime(pc.getCommandId(), CMDHISTORY_SLE_REQ_KEY,
                            TimeEncoding.getWallclockTime(), "OK");
                }

            }

        }
    }

    private boolean isUplinkPossible() {
        return (csuh != null) && csuh.isConnected() && pendingFrames.size() < maxPendingFrames
                && sleState == org.yamcs.sle.AbstractServiceUserHandler.State.ACTIVE && prodStatus==CltuProductionStatus.operational;
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

    @Override
    public void shutDown() {
        super.shutDown();
    }

    private void sleBind() {
        csuh.bind().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to bind: " + t.getMessage());
                return null;
            }
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
            csuh.schedulePeriodicStatusReport(10);
            return null;
        });
    }

    private void onCltuRadiated(int cltuId) {
        TcTransferFrame tf = pendingFrames.remove(cltuId);
        if (tf == null) {
            log.warn("Received cltu-radiated event for unknown cltuId {}", cltuId);
            return;
        }
        for (PreparedCommand pc : tf.getCommands()) {
            commandHistoryPublisher.publishWithTime(pc.getCommandId(), CommandHistoryPublisher.ACK_SENT_CNAME_PREFIX,
                    TimeEncoding.getWallclockTime(), "OK");
        }
        uplinkReadySemaphore.release();
    }

    private void failBypassFrame(TcTransferFrame tf, String reason) {
        for (PreparedCommand pc : tf.getCommands()) {
            commandHistoryPublisher.commandFailed(pc.getCommandId(), "SLE disconnected");
        }
    }

    class MyMonitor implements CltuSleMonitor {

        @Override
        public void connected() {
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
        public void onCltuStatusReport(CltuStatusReportInvocation cltuStatusReportInvocation) {
            prodStatus = CltuProductionStatus.byId(cltuStatusReportInvocation.getCltuProductionStatus().intValue());
        }

        @Override
        public void onAsyncNotify(CltuAsyncNotifyInvocation cltuAsyncNotifyInvocation) {
            if (log.isTraceEnabled()) {
                log.trace(cltuAsyncNotifyInvocation.toString());
            }
            CltuNotification cn = cltuAsyncNotifyInvocation.getCltuNotification();
            prodStatus = CltuProductionStatus.byId(cltuAsyncNotifyInvocation.getProductionStatus().intValue());

            if (cn.getCltuRadiated() != null) {
                CltuLastProcessed clp = cltuAsyncNotifyInvocation.getCltuLastProcessed();
                int cltuId = clp.getCltuProcessed().getCltuIdentification().value.intValue();
                onCltuRadiated(cltuId);
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

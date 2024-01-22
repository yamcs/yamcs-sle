package org.yamcs.sle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.jsle.CcsdsTime;
import org.yamcs.jsle.Constants.CltuProductionStatus;
import org.yamcs.jsle.Constants.UplinkStatus;
import org.yamcs.jsle.Isp1Handler;
import org.yamcs.jsle.ParameterName;
import org.yamcs.jsle.SleException;
import org.yamcs.jsle.SleParameter;
import org.yamcs.jsle.user.CltuServiceUserHandler;
import org.yamcs.jsle.user.CltuSleMonitor;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.sle.SleConfig.Endpoint;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.LinkAction;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.DownlinkManagedParameters.FrameErrorDetection;
import org.yamcs.tctm.ccsds.TcTransferFrame;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.SystemParameter;
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
public class TcSleLink extends AbstractTcFrameLink implements Runnable, SleLink {
    FrameErrorDetection errorCorrection;

    SleConfig sconf;
    int endpointIndex = -1;

    CltuServiceUserHandler csuh;
    CltuSleMonitor sleMonitor;
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

    org.yamcs.jsle.State sleState = org.yamcs.jsle.State.UNBOUND;

    org.yamcs.jsle.State requestedState = org.yamcs.jsle.State.UNBOUND;

    private SystemParameter sp_sleState, sp_cltuStatus, sp_numPendingFrames;
    final static AggregateMemberNames cltuStatusMembers = AggregateMemberNames.get(new String[] { "productionStatus",
            "uplinkStatus", "numCltuReceived", "numCltuProcessed", "numCltuRadiated", "cltuBufferAvailable" });

    private volatile ParameterValue cltuStatus;
    private Thread thread;

    boolean startSleOnEnable = true;


    private LinkAction startAction = new LinkAction("start", "Start SLE") {
        @Override
        public JsonObject execute(Link link, JsonObject jsonObject) {
            if(!isEffectivelyDisabled()) {
                if (requestedState == org.yamcs.jsle.State.UNBOUND) {
                    connectAndBind(true);
                } else {
                    sleStart();
                }
            }
            return null;
        }
    };
    private LinkAction stopAction = new LinkAction("stop", "Stop SLE") {
        @Override
        public JsonObject execute(Link link, JsonObject jsonObject) {
            sleStop();
            return null;
        }
    };

    @Override
    public void init(String yamcsInstance, String name, YConfiguration config) {
        super.init(yamcsInstance, name, config);

        this.maxPendingFrames = config.getInt("maxPendingFrames", 20);
        this.waitForUplinkMsec = config.getInt("waitForUplinkMsec", 5000);
        this.startSleOnEnable = config.getBoolean("startSleOnEnable", true);

        YConfiguration slec = YConfiguration.getConfiguration("sle").getConfig("Providers")
                .getConfig(config.getString("sleProvider"));
        sconf = new SleConfig(slec, "cltu");

        sleMonitor = new MyMonitor();
    }

    private synchronized void connectAndBind(boolean startSle) {
        if (!isRunningAndEnabled()) {
            return;
        }
        requestedState = org.yamcs.jsle.State.READY;

        if (endpointIndex < 0) {
            endpointIndex = 0;
        }
        Endpoint endpoint = sconf.getEndpoint(endpointIndex);
        eventProducer.sendInfo("Connecting to SLE FCLTU service " + endpoint.getHost() + ":" + endpoint.getPort() + " as user " +
                sconf.auth.getMyUsername());
        csuh = new CltuServiceUserHandler(sconf.auth, sconf.attr);
        csuh.setVersionNumber(sconf.versionNumber);
        csuh.setAuthLevel(sconf.authLevel);
        csuh.setReturnTimeoutSec(sconf.returnTimeoutSec);

        csuh.addMonitor(sleMonitor);

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
                ch.pipeline().addLast(csuh);
            }
        });
        b.connect(endpoint.getHost(), endpoint.getPort()).addListener(f -> {
            if (!f.isSuccess()) {
                eventProducer.sendWarning("Failed to connect to the SLE provider: " + f.cause().getMessage());
                csuh = null;
                ++endpointIndex;
                if (endpointIndex >= sconf.getEndpointCount()) {
                    endpointIndex = -1;
                }
                if (endpointIndex >= 0 && sconf.roundRobinIntervalMillis >= 0) {
                    workerGroup.schedule(() -> connectAndBind(startSle), sconf.roundRobinIntervalMillis, TimeUnit.MILLISECONDS);
                } else if (sconf.reconnectionIntervalSec >= 0) {
                    workerGroup.schedule(() -> connectAndBind(startSle), sconf.reconnectionIntervalSec, TimeUnit.SECONDS);
                }
            } else {
                sleBind(startSle);
            }
        });
    }

    @Override
    public void run() {
        while (isRunningAndEnabled()) {
            TcTransferFrame tf = multiplexer.getFrame();
            if (tf != null) {

                byte[] data = tf.getData();
                if (log.isTraceEnabled()) {
                    log.trace("New TC frame: {}\n\tdata: {}", tf, StringConverter.arrayToHexString(data));
                }

                data = encodeCltu(tf.getVirtualChannelId(), data);

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
                if (log.isTraceEnabled()) {
                    log.trace("Sending CLTU of size {}: {}", data.length, StringConverter.arrayToHexString(data));
                }
                int id = csuh.transferCltu(data);
                pendingFrames.put(id, tf);
                if (tf.getCommands() != null) {
                    for (PreparedCommand pc : tf.getCommands()) {
                        commandHistoryPublisher.publishAck(pc.getCommandId(), CMDHISTORY_SLE_REQ_KEY,
                                TimeEncoding.getWallclockTime(), AckStatus.OK);
                    }
                }
                frameCount++;
            }
        }
    }

    private boolean isUplinkPossible() {
        return (csuh != null) && csuh.isConnected() && pendingFrames.size() < maxPendingFrames
                && sleState == org.yamcs.jsle.State.ACTIVE
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

    private void sleBind(boolean startSle) {
        requestedState = org.yamcs.jsle.State.READY;

        csuh.bind().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to bind: " + t.getMessage());
                return null;
            }
            log.debug("BIND successful");
            if (startSle) {
                sleStart();
            }
            return null;
        });
    }

    private void sleStart() {
        requestedState = org.yamcs.jsle.State.ACTIVE;
        log.debug("Starting SLE service");

        csuh.start().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to start: " + t);
                return null;
            }
            log.debug("Successfully started the service");
            csuh.schedulePeriodicStatusReport(10);
            return null;
        });
    }

    private void sleStop() {
        requestedState = org.yamcs.jsle.State.READY;
        log.debug("Stopping SLE service");

        csuh.stop().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to stop: " + t);
                return null;
            }
            log.debug("Successfully stopped the service");
            return null;
        });
    }

    private void onCltuRadiated(CltuOk cltuOk) {
        int cltuId = cltuOk.getCltuIdentification().value.intValue();
        CcsdsTime time = CcsdsTime.fromSle(cltuOk.getRadiationStopTime());
        long yamcsTime = TimeEncoding.fromUnixMillisec(time.toJavaMillisec());
        TcTransferFrame tf = pendingFrames.remove(cltuId);
        if (tf == null) {
            log.warn("Received cltu-radiated event for unknown cltuId {}", cltuId);
            return;
        }

        if (tf.isBypass()) {
            ackBypassFrame(tf);
        }

        for (PreparedCommand pc : tf.getCommands()) {
            commandHistoryPublisher.publishAck(pc.getCommandId(), CMDHISTORY_SLE_RADIATED_KEY, yamcsTime, AckStatus.OK,
                    time.toStringPico());
        }

        uplinkReadySemaphore.release();
    }

    @Override
    public void setupSystemParameters(SystemParametersService sps) {
        super.setupSystemParameters(sps);

        sp_sleState = sps.createEnumeratedSystemParameter(linkName + "/sleState", org.yamcs.jsle.State.class,
                "The state of the SLE connection");

        sp_numPendingFrames = sps.createSystemParameter(linkName + "/numPendingFrames", Type.SINT32,
                "the number of pending (waiting to be radiated) frames");

        AggregateParameterType spStatusType = new AggregateParameterType.Builder()
                .setName("cltuStatus_type")
                .addMember(new Member("productionStatus",
                        sps.createEnumeratedParameterType(CltuProductionStatus.class)))
                .addMember(new Member("uplinkStatus",
                        sps.createEnumeratedParameterType(UplinkStatus.class)))
                .addMember(new Member("numCltuReceived", sps.getBasicType(Type.SINT32)))
                .addMember(new Member("numCltuProcessed", sps.getBasicType(Type.SINT32)))
                .addMember(new Member("numCltuRadiated", sps.getBasicType(Type.SINT32)))
                .addMember(new Member("cltuBufferAvailable", sps.getBasicType(Type.UINT64)))
                .build();

        sp_cltuStatus = sps.createSystemParameter(linkName + "/cltuStatus", spStatusType,
                "Status of the CLTU uplink as received from the Ground Station");
    }

    @Override
    protected void collectSystemParameters(long time, List<ParameterValue> list) {
        list.add(SystemParametersService.getPV(sp_sleState, time, sleState));
        list.add(SystemParametersService.getPV(sp_numPendingFrames, time, pendingFrames.size()));
        if (cltuStatus != null) {
            list.add(cltuStatus);
            cltuStatus = null;
        }
    }

    @Override
    protected void doDisable() {
        requestedState = org.yamcs.jsle.State.UNBOUND;
        startAction.setEnabled(false);
        stopAction.setEnabled(false);

        if (thread != null) {
            thread.interrupt();
        }
        if (csuh != null) {
            Utils.sleStop(csuh, sconf, eventProducer);
            csuh = null;
        }
        eventProducer.sendInfo("SLE link disabled");
    }

    @Override
    protected void doEnable() {
        endpointIndex = -1;
        connectAndBind(startSleOnEnable);
        thread = new Thread(this);
        thread.start();
        eventProducer.sendInfo("SLE link enabled");
    }

    @Override
    protected void doStart() {
        if (!isDisabled()) {
            doEnable();
        }
        notifyStarted();

        addAction(startAction);
        addAction(stopAction);

        startAction.setEnabled(false);
        stopAction.setEnabled(false);
    }

    @Override
    protected void doStop() {
        doDisable();
        multiplexer.quit();
        notifyStopped();
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
    public String getDetailedStatus() {
        return "SLE " + sleState;
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

            cltuStatus = SystemParametersService.getPV(sp_cltuStatus, getCurrentTime(), tmp);
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

    public CompletableFuture<Void> throwEvent(int eventIdentifier, byte[] eventQualifier) {
        CltuServiceUserHandler _csuh = csuh;
        if (_csuh == null || !_csuh.isConnected()) {
            throw new SleException("SLE link is not connected");
        }
        return _csuh.throwEvent(eventIdentifier, eventQualifier);

    }

    @Override
    public CompletableFuture<SleParameter> getParameter(ParameterName paraName) {
        CltuServiceUserHandler _csuh = csuh;
        if (_csuh == null || !_csuh.isConnected()) {
            throw new SleException("link not connected");
        }
        return _csuh.getParameter(paraName.id());
    }

    @Override
    public boolean isCommandingAvailable() {
        if (isDisabled()) {
            return false;
        } else if (getParent() != null) {
            return !getParent().isEffectivelyDisabled();
        } else {
            return requestedState == org.yamcs.jsle.State.ACTIVE;
        }
    }
}

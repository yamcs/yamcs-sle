package org.yamcs.sle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.yamcs.YConfiguration;
import org.yamcs.actions.ActionResult;
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
import org.yamcs.sle.EndpointHandler.Endpoint;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.LinkAction;
import org.yamcs.tctm.ccsds.AbstractTcFrameLink;
import org.yamcs.tctm.ccsds.DownlinkManagedParameters.FrameErrorDetection;
import org.yamcs.tctm.ccsds.UplinkTransferFrame;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.SystemParameter;
import org.yamcs.xtce.util.AggregateMemberNames;

import com.google.gson.JsonObject;

import ccsds.sle.transfer.service.cltu.outgoing.pdus.CltuAsyncNotifyInvocation;
import ccsds.sle.transfer.service.cltu.outgoing.pdus.CltuStatusReportInvocation;
import ccsds.sle.transfer.service.cltu.structures.CltuLastOk;
import ccsds.sle.transfer.service.cltu.structures.CltuLastOk.CltuOk;
import ccsds.sle.transfer.service.cltu.structures.CltuNotification;
import ccsds.sle.transfer.service.cltu.structures.DiagnosticCltuTransferData;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
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

    // the current connection; a connection stops being current when the link is disabled or the connection is lost.
    // Set only in connectAndBind and cleared via takeHandler/clearIfCurrent (synchronized), at most one at a time.
    volatile CltuServiceUserHandler csuh;
    Map<Integer, UplinkTransferFrame> pendingFrames = new ConcurrentHashMap<>();
    // held by the sender thread from transferCltu until the frame is in pendingFrames, and by whoever removes frames,
    // so that a fast reply from the provider cannot be processed before the frame is registered
    private final Object pendingLock = new Object();
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

    /**
     * What the provider does with the buffered CLTUs when the association is aborted (CCSDS 912.1-B-5 4.1.5.3)
     */
    enum ProtocolAbortMode {
        /** buffered CLTUs are discarded (the CCSDS default) */
        FLUSH,
        /** buffered CLTUs continue to be radiated */
        CONTINUE,
        /** query the provider after each BIND, assume FLUSH until it answers */
        QUERY
    }

    // configured mode
    ProtocolAbortMode protocolAbortMode;
    // mode in effect; FLUSH or CONTINUE
    volatile ProtocolAbortMode effectiveProtocolAbortMode;

    EndpointHandler endpointHandler;

    private LinkAction startAction = new LinkAction("start", "Start SLE") {
        @Override
        public void execute(Link link, JsonObject jsonObject, ActionResult result) {
            if (!isDisabled()) {
                if (requestedState == org.yamcs.jsle.State.UNBOUND) {
                    connectAndBind(true);
                } else {
                    sleStart();
                }
            }
            result.complete();
        }
    };
    private LinkAction stopAction = new LinkAction("stop", "Stop SLE") {
        @Override
        public void execute(Link link, JsonObject jsonObject, ActionResult result) {
            sleStop();
            result.complete();
        }
    };

    @Override
    public void init(String yamcsInstance, String name, YConfiguration config) {
        super.init(yamcsInstance, name, config);

        this.maxPendingFrames = config.getInt("maxPendingFrames", 20);
        this.waitForUplinkMsec = config.getInt("waitForUplinkMsec", 5000);
        this.startSleOnEnable = config.getBoolean("startSleOnEnable", true);
        this.protocolAbortMode = config.getEnum("protocolAbortMode", ProtocolAbortMode.class, ProtocolAbortMode.FLUSH);
        this.effectiveProtocolAbortMode = protocolAbortMode == ProtocolAbortMode.CONTINUE ? ProtocolAbortMode.CONTINUE
                : ProtocolAbortMode.FLUSH;

        YConfiguration slec = YConfiguration.getConfiguration("sle").getConfig("Providers")
                .getConfig(config.getString("sleProvider"));
        sconf = new SleConfig(slec, "cltu");
        this.endpointHandler = new EndpointHandler(sconf);
    }

    /**
     * Connects to the provider and binds, unless the link is disabled or there is already a connection.
     * <p>
     * The second condition matters because reconnections are scheduled from several places (connection lost,
     * connection failed, start action); a second connection would leave the first one bound to the service instance
     * and never closed, after which the provider refuses all binds with 'already bound' (yamcs-sle#16).
     */
    private synchronized void connectAndBind(boolean startSle) {
        if (!isRunningAndEnabled()) {
            return;
        }
        if (csuh != null) {
            log.debug("Already connected or connecting to the SLE provider");
            return;
        }
        requestedState = org.yamcs.jsle.State.READY;

        Endpoint endpoint = endpointHandler.next();
        eventProducer.sendInfo(
                "Connecting to SLE FCLTU service " + endpoint.getHost() + ":" + endpoint.getPort() + " as user " +
                        sconf.auth.getMyUsername());
        CltuServiceUserHandler h = new CltuServiceUserHandler(sconf.auth, sconf.attr);
        h.setVersionNumber(sconf.versionNumber);
        h.setAuthLevel(sconf.authLevel);
        h.setReturnTimeoutSec(sconf.returnTimeoutSec);

        h.addMonitor(new MyMonitor(h));
        csuh = h;

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
            } else if (csuh != h) {
                // the link has been disabled while connecting; h.shutdown() does nothing before the channel is active
                cf.channel().close();
            } else {
                sleBind(h, startSle);
            }
        });
    }

    /**
     * Clears {@link #csuh} if it is h
     *
     * @return true if h was the current connection
     */
    private synchronized boolean clearIfCurrent(CltuServiceUserHandler h) {
        if (csuh == h) {
            csuh = null;
            return true;
        }
        return false;
    }

    /**
     * Clears {@link #csuh}
     *
     * @return the connection which was current (possibly null)
     */
    private synchronized CltuServiceUserHandler takeHandler() {
        CltuServiceUserHandler h = csuh;
        csuh = null;
        return h;
    }

    @Override
    public void run() {
        while (isRunningAndEnabled()) {
            UplinkTransferFrame tf = multiplexer.getFrame();
            if (tf != null) {
                // an exception must not end this thread, otherwise the link stays enabled but nothing is sent anymore
                try {
                    sendFrame(tf);
                } catch (Exception e) {
                    log.error("Error sending TC frame {}", tf, e);
                    if (tf.isBypass() && !pendingFrames.containsValue(tf)) {
                        failBypassFrame(tf, "Error sending the frame: " + e);
                    }
                }
            }
        }
    }

    private void sendFrame(UplinkTransferFrame tf) {
        byte[] data = tf.getData();
        if (log.isTraceEnabled()) {
            log.trace("New TC frame: {}\n\tdata: {}", tf, StringConverter.arrayToHexString(data));
        }

        data = encodeCltu(tf.getVirtualChannelId(), data);

        if (!isUplinkPossible() && waitForUplinkMsec > 0) {
            waitForUplink(waitForUplinkMsec);
        }

        // csuh is set to null on disconnection (Netty thread) and on disable; read it only once
        CltuServiceUserHandler _csuh = csuh;
        if (_csuh == null || !isUplinkPossible()) {
            log.debug("TC frame {} dropped because uplink is not available", tf);
            if (tf.isBypass()) {
                failBypassFrame(tf, "SLE uplink not available");
            }
            return;
        }
        if (log.isTraceEnabled()) {
            log.trace("Sending CLTU of size {}: {}", data.length, StringConverter.arrayToHexString(data));
        }
        synchronized (pendingLock) {
            int id;
            try {
                id = _csuh.transferCltu(data);
            } catch (Exception e) {
                // e.g. the channel became inactive in the meantime
                log.warn("Failed to send CLTU: {}", e.toString());
                if (tf.isBypass()) {
                    failBypassFrame(tf, "SLE uplink not available");
                }
                return;
            }
            pendingFrames.put(id, tf);
        }
        dataOut(1, data.length);
        if (tf.getCommands() != null) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(), CMDHISTORY_SLE_REQ_KEY,
                        TimeEncoding.getWallclockTime(), AckStatus.OK);
            }
        }
        frameCount++;
    }

    private boolean isUplinkPossible() {
        CltuServiceUserHandler _csuh = csuh;
        return (_csuh != null) && _csuh.isConnected() && pendingFrames.size() < maxPendingFrames
                && sleState == org.yamcs.jsle.State.ACTIVE
                && prodStatus == CltuProductionStatus.operational;
    }

    /**
     * Removes from {@link #pendingFrames} the frames that the provider discarded without notification, failing the
     * commands of the bypass frames if failBypass is true.
     * <p>
     * If keepOldest is true, the frame with the lowest CLTU id is kept: the provider may be radiating it, in which case
     * it completes the radiation and reports it (CCSDS 912.1-B-5 3.5.3.1).
     */
    private void discardPendingFrames(String reason, boolean failBypass, boolean keepOldest) {
        List<UplinkTransferFrame> discarded = new ArrayList<>();
        synchronized (pendingLock) {
            Integer oldest = null;
            if (keepOldest) {
                for (Integer id : pendingFrames.keySet()) {
                    if (oldest == null || id < oldest) {
                        oldest = id;
                    }
                }
            }
            var it = pendingFrames.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (!e.getKey().equals(oldest)) {
                    it.remove();
                    discarded.add(e.getValue());
                }
            }
        }
        if (failBypass) {
            for (UplinkTransferFrame tf : discarded) {
                if (tf.isBypass()) {
                    failBypassFrame(tf, reason);
                }
            }
        }
        uplinkReadySemaphore.release();
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

    private void sleBind(CltuServiceUserHandler h, boolean startSle) {
        requestedState = org.yamcs.jsle.State.READY;

        h.bind().handle((v, t) -> {
            if (t != null) {
                if (csuh == h) {
                    // the disconnection triggers a new attempt after reconnectionIntervalSec
                    eventProducer.sendWarning("Failed to bind: " + t.getMessage() + "; closing the connection");
                }
                h.shutdown();
                return null;
            }
            if (csuh != h) {
                return null; // the link has been disabled in the meantime, the connection is being closed
            }
            log.debug("BIND successful");
            if (protocolAbortMode == ProtocolAbortMode.QUERY) {
                queryProtocolAbortMode(h).whenComplete((v1, t1) -> {
                    if (startSle && csuh == h) {
                        sleStart();
                    }
                });
            } else if (startSle) {
                sleStart();
            }
            return null;
        });
    }

    /**
     * Retrieves the protocol-abort-mode from the provider and sets the {@link #effectiveProtocolAbortMode}.
     * <p>
     * If the provider does not answer, the mode in effect is left unchanged (FLUSH unless a previous query returned
     * otherwise).
     * <p>
     * The returned future always completes normally.
     */
    private CompletableFuture<Void> queryProtocolAbortMode(CltuServiceUserHandler h) {
        CompletableFuture<SleParameter> cf;
        try {
            cf = h.getParameter(ParameterName.protocolAbortMode);
        } catch (Exception e) {
            cf = CompletableFuture.failedFuture(e);
        }
        return cf.handle((p, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to retrieve protocol-abort-mode from the provider: " + t.getMessage()
                        + "; assuming " + effectiveProtocolAbortMode.name().toLowerCase());
                return null;
            }
            Object v = p.getParameterValue();
            // ASN.1: abort (0), continue (1)
            if (v instanceof Integer && ((Integer) v == 0 || (Integer) v == 1)) {
                boolean cont = (Integer) v == 1;
                effectiveProtocolAbortMode = cont ? ProtocolAbortMode.CONTINUE : ProtocolAbortMode.FLUSH;
                eventProducer.sendInfo("Provider protocol-abort-mode: " + (cont ? "continue" : "abort (flush)"));
            } else {
                eventProducer.sendWarning("Unexpected protocol-abort-mode value received from the provider: " + v
                        + "; assuming " + effectiveProtocolAbortMode.name().toLowerCase());
            }
            return null;
        });
    }

    private void sleStart() {
        CltuServiceUserHandler h = csuh;
        if (h == null) {
            return;
        }
        requestedState = org.yamcs.jsle.State.ACTIVE;
        log.debug("Starting SLE service");

        // a frame kept at the previous STOP (because it might have been in radiation) and still not reported
        // was discarded by the provider
        if (!pendingFrames.isEmpty()) {
            discardPendingFrames("SLE stopped, CLTU discarded by the provider", true, false);
        }

        h.start().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to start: " + t);
                return null;
            }
            log.debug("Successfully started the service");
            h.schedulePeriodicStatusReport(10);
            return null;
        });
    }

    private void sleStop() {
        CltuServiceUserHandler h = csuh;
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
            // the provider discards the CLTUs not yet in radiation, without notification (CCSDS 912.1-B-5 3.5.3.1)
            discardPendingFrames("SLE stopped, CLTU discarded by the provider", true, true);
            return null;
        });
    }

    private void onCltuRadiated(CltuOk cltuOk) {
        int cltuId = cltuOk.getCltuIdentification().value.intValue();
        CcsdsTime time = CcsdsTime.fromSle(cltuOk.getRadiationStopTime());
        long yamcsTime = TimeEncoding.fromUnixMillisec(time.toJavaMillisec());
        UplinkTransferFrame tf;
        synchronized (pendingLock) {
            tf = pendingFrames.remove(cltuId);
        }
        if (tf == null) {
            log.warn("Received cltu-radiated event for unknown cltuId {}", cltuId);
            return;
        }

        if (tf.isBypass()) {
            ackBypassFrame(tf);
        }

        // BC frames (e.g. COP-1 initiation with unlock) carry no commands
        if (tf.getCommands() != null) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(), CMDHISTORY_SLE_RADIATED_KEY, yamcsTime,
                        AckStatus.OK, time.toStringPico());
            }
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
        CltuServiceUserHandler h = takeHandler();
        if (h != null) {
            Utils.sleStop(h, sconf, eventProducer);
        }
        sleState = org.yamcs.jsle.State.UNBOUND;
        // The provider discards the buffered CLTUs when the association ends (CCSDS 912.1-B-5 2.6.4.4). The
        // notifications of the connection being closed are ignored (see MyMonitor), so the pending frames are
        // discarded now, before a new connection may be made.
        discardPendingFrames("SLE link disabled", true, false);
        eventProducer.sendInfo("SLE link disabled");
    }

    @Override
    protected void doEnable() {
        endpointHandler.reset();
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
     * 
     * @return Status.OK or Status.UNAVAIL
     */
    @Override
    protected Status connectionStatus() {
        return sleState != org.yamcs.jsle.State.UNBOUND && sleState == requestedState ? Status.OK : Status.UNAVAIL;
    }

    @Override
    public Map<String, Object> getExtraInfo() {
        return Map.of("SLE state", sleState,
                "Protocol abort mode", effectiveProtocolAbortMode.name().toLowerCase());
    }

    @Override
    public String getDetailedStatus() {
        return "SLE " + sleState;
    }

    /**
     * Monitors one connection. Once the connection is no longer the current one (the link has been disabled or the
     * connection lost), its callbacks are ignored, except for logging: otherwise a closing connection could shut
     * down or change the state of a newer one.
     */
    class MyMonitor implements CltuSleMonitor {
        final CltuServiceUserHandler suh;

        MyMonitor(CltuServiceUserHandler suh) {
            this.suh = suh;
        }

        boolean isCurrent() {
            return csuh == suh;
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

            // When the association ends, the provider discards the buffered CLTUs (CCSDS 912.1-B-5 2.6.4.4), unless it
            // was a protocol abort and the protocol-abort-mode is 'continue' (4.1.5.3) in which case it keeps radiating
            // them. Either way we will receive no further notifications for them.
            // If we initiated the unbind (link disabled), the CLTUs are discarded regardless of the mode.
            boolean flushed = effectiveProtocolAbortMode == ProtocolAbortMode.FLUSH
                    || requestedState == org.yamcs.jsle.State.UNBOUND;
            if (!flushed && !pendingFrames.isEmpty()) {
                eventProducer.sendWarning(pendingFrames.size() + " CLTUs pending at disconnection;"
                        + " with protocol-abort-mode 'continue' the provider may still radiate them");
            }
            discardPendingFrames("SLE disconnected", flushed, false);
            sleState = org.yamcs.jsle.State.UNBOUND;
            endpointHandler.reset();

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
                if (!isDisabled()) {
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
            if (!isCurrent()) {
                return;
            }
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
            if (!isCurrent()) {
                return;
            }
            CltuNotification cn = cltuAsyncNotifyInvocation.getCltuNotification();
            prodStatus = CltuProductionStatus.byId(cltuAsyncNotifyInvocation.getProductionStatus().intValue());

            if (cn.getCltuRadiated() != null) {
                CltuLastOk lastOk = cltuAsyncNotifyInvocation.getCltuLastOk();
                CltuOk cltuOk = (lastOk == null) ? null : lastOk.getCltuOk();
                if (cltuOk == null) {
                    log.warn("Received cltu-radiated notification without cltu-last-OK: {}", cltuAsyncNotifyInvocation);
                    uplinkReadySemaphore.release();
                } else {
                    onCltuRadiated(cltuOk);
                }
            } else if (cn.getProductionInterrupted() != null) {
                eventProducer.sendInfo("CLTU Production interrupted");
                // buffered CLTUs are discarded (CCSDS 912.1-B-5 3.7.3.1)
                discardPendingFrames("CLTU production interrupted", true, false);
            } else if (cn.getProductionHalted() != null) {
                eventProducer.sendInfo("CLTU Production halted");
                discardPendingFrames("CLTU production halted", true, false);
            } else if (cn.getSlduExpired() != null) {
                eventProducer.sendWarning("CLTU expired before radiation; the provider discarded the buffered CLTUs");
                discardPendingFrames("CLTU expired", true, false);
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
            if (!isCurrent()) {
                return;
            }
            UplinkTransferFrame tf;
            synchronized (pendingLock) {
                tf = pendingFrames.remove(cltuId);
            }
            if (tf == null) {
                log.warn("Received negative transfer for unknown cltuId {}: {}", cltuId, negativeResult);
            } else if (tf.isBypass()) {
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

    /**
     * Reports the link as effectively disabled unless the SLE service is ACTIVE.
     * <p>
     * This stretches the usual meaning of the method (disabled by itself or by a parent link): it is used here because
     * the virtual channel sub-links, which are the ones receiving the commands, derive their isCommandingAvailable()
     * from the parent's isEffectivelyDisabled(). Without this, a bound but not started link (READY) would accept
     * commands and fail them after waitForUplinkMsec (#17).
     * <p>
     */
    @Override
    public boolean isEffectivelyDisabled() {
        if (isDisabled() || sleState != org.yamcs.jsle.State.ACTIVE) {
            return true;
        }
        return super.isEffectivelyDisabled();
    }
}

package org.yamcs.sle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.sle.Constants.DeliveryMode;
import org.yamcs.sle.Constants.FrameQuality;
import org.yamcs.sle.Constants.UnbindReason;
import org.yamcs.sle.user.RafServiceUserHandler;
import org.yamcs.sle.user.RcfServiceUserHandler;

/**
 * Receives TM frames via SLE. The Virtual Channel configuration is identical with the configuration of
 * {@link OfflineTmSleLink}.
 * <p>
 * The SLE specific settings are loaded from sle.yaml based on the sleProvider key specified in the link configuration.
 * The description of the sle.yaml configuration parameters are as follows:
 * <table border=1>
 * <caption>Configuration Parameters</caption>
 * <tr>
 * <td>initiatorId</td>
 * <td>identifier of the local application</td>
 * </tr>
 * <tr>
 * <td>responderPortId</td>
 * <td>Responder Port Identifier</td>
 * </tr>
 * <tr>
 * <td>serviceInstance</td>
 * <td>Used in the bind request to select the instance number of the remote service.This number together with the
 * deliverymode specify the so called service name identifier (raf=onltX where X is the number)</td>
 * </tr>
 * <tr>
 * <td>versionNumber</td>
 * <td>the version number is sent in the bind invocation. We only support the version of the SLE valid in April-2019;
 * however this field is not checked.</td>
 * <td></td>
 * </tr>
 * <tr>
 * <td>myUsername</td>
 * <td>username that is passed in outgoing SLE messages. A corresponding password has to be specified (in hexadecimal)
 * in the security.yaml file.</td>
 * </tr>
 * <tr>
 * <td>peerUsername</td>
 * <td>username that is used to verify the incoming SLE messages. A corresponding password has to be specified (in
 * hexadecimal) in the security.yaml file.</td>
 * </tr>
 * <tr>
 * <td>authLevel</td>
 * <td>one of NONE, BIND or ALL - it configures which incoming and outgoing PDUs contain authentication
 * information.</td>
 * </tr>
 * 
 * </table>
 * 
 * 
 * @author nm
 *
 */
public class OfflineTmSleLink extends AbstractTmSleLink {
    RacfSleMonitor sleMonitor = new MyMonitor();
    LinkedBlockingQueue<RequestRange> requestQueue = new LinkedBlockingQueue<>();
    volatile int numRequests;
    int lastReqFrameCount;

    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config, DeliveryMode.rtnOffline);
        sconf.reconnectionIntervalSec = -1;
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        Utils.sleStop(rsuh, sconf, eventProducer);
        notifyStopped();
    }

    void sleStop(UnbindReason unbindReason) {

    }

    void sleStart() {
        RequestRange rr = requestQueue.poll();
        if (rr == null) {
            eventProducer.sendInfo("All requests finished, disconnecting from SLE");
            Utils.sleStop(rsuh, sconf, eventProducer);
            rsuh = null;
        } else {
            eventProducer.sendInfo("Starting an offline request for interval " + rr);
            lastReqFrameCount = 0;
            CompletableFuture<Void> cf;
            if (gvcid == null) {
                cf = ((RafServiceUserHandler) rsuh).start(rr.start, rr.stop);
            } else {
                cf = ((RcfServiceUserHandler) rsuh).start(rr.start, rr.stop, gvcid);
            }

            cf.handle((v, t) -> {
                if (t != null) {
                    eventProducer.sendWarning("Request for interval " + rr + " failed: " + t);
                    // we can do nothing about it, try maybe there is a new request
                    sleStart();
                    return null;
                }
                numRequests++;
                return null;
            });
        }
    }

    @Override
    public void acceptFrame(CcsdsTime ert, AntennaId antennaId, int dataLinkContinuity,
            FrameQuality frameQuality, byte[] privAnn, byte[] data) {
        lastReqFrameCount++;
        super.acceptFrame(ert, antennaId, dataLinkContinuity, frameQuality, privAnn, data);
    }

    @Override
    protected void doDisable() {
        if (rsuh != null) {
            Utils.sleStop(rsuh, sconf, eventProducer);
            rsuh = null;
        }
    }

    @Override
    protected void doEnable() throws Exception {
        if (!requestQueue.isEmpty()) {
            connect();
        }
    }

    @Override
    public void onEndOfData() {
        eventProducer.sendInfo("SLE end of data received. " + lastReqFrameCount + " frames received");

        rsuh.stop().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to stop: " + t.getMessage());
                return null;
            }
            sleStart();
            return null;
        });
    }

    public synchronized void addRequest(CcsdsTime start, CcsdsTime stop) {
        requestQueue.add(new RequestRange(start, stop));
        if (rsuh == null) {
            connect();
        }
    }

    @Override
    protected Status connectionStatus() {
        if (requestQueue.isEmpty()) {
            return Status.OK;
        } else {
            return (rsuh != null && rsuh.getState() == org.yamcs.sle.State.ACTIVE) ? Status.OK : Status.UNAVAIL;
        }
    }

    @Override
    public long getDataOutCount() {
        return numRequests;
    }

    static class RequestRange {
        CcsdsTime start;
        CcsdsTime stop;

        public RequestRange(CcsdsTime start, CcsdsTime stop) {
            this.start = start;
            this.stop = stop;
        }

        @Override
        public String toString() {
            return "[" + start + ", " + stop + "]";
        }
    }
}

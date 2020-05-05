package org.yamcs.sle;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.sle.RafSleMonitor;
import org.yamcs.sle.api.OfflineRange;
import org.yamcs.sle.CcsdsTime;
import org.yamcs.sle.Constants.DeliveryMode;

/**
 * Receives TM frames via SLE. The Virtual Channel configuration is identical with the configuration of
 * {@link OfflineTmFrameLink}.
 * <p>
 * The SLE specific settings are loaded from sle.yaml based on the sleProvider key specified in the link configuration.
 * The description of the sle.yaml configuration parameters are as follows:
 * <table border=1>
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
public class OfflineTmFrameLink extends AbstractTmSleLink {
    RafSleMonitor sleMonitor = new MyMonitor();

    LinkedBlockingQueue<OfflineRange> requestQueue = new LinkedBlockingQueue<>();

    public OfflineTmFrameLink(String instance, String name, YConfiguration config) throws ConfigurationException {
        super(instance, name, config, DeliveryMode.rtnOffline);
        reconnectionIntervalSec = -1;
    }

    @Override
    protected void doStart() {
        setupSystemParameters();
        notifyStarted();
    }

    @Override
    protected void doStop() {
        if (rsuh != null) {
            rsuh.shutdown();
            rsuh = null;
        }
        notifyStopped();
    }

    void sleStart() {
        OfflineRange or = requestQueue.poll();
        if (or == null) {
            eventProducer.sendInfo("All requests finished, disconnecting from SLE");
            rsuh.shutdown();
            rsuh = null;
        } else {
            CcsdsTime t0 = CcsdsTime.fromUnix(or.getStart().getSeconds(), 1000 * or.getStart().getNanos());
            CcsdsTime t1 = CcsdsTime.fromUnix(or.getStop().getSeconds(), 1000 * or.getStop().getNanos());
            eventProducer.sendInfo("Starting an offline request for interval [" + t0 + ", " + t1);
            rsuh.start(t0, t1).handle((v, t) -> {
                if (t != null) {
                    eventProducer.sendWarning("Failed to start: " + t.getMessage());
                    return null;
                }
                return null;
            });
        }
    }

    @Override
    protected void doDisable() {
        if (rsuh != null) {
            rsuh.shutdown();
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
        eventProducer.sendInfo("SLE end of data received");
        rsuh.stop().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to stop: " + t.getMessage());
                return null;
            }
            sleStart();
            return null;
        });
    }

    public synchronized void addRequests(List<OfflineRange> rangesList) {
        requestQueue.addAll(rangesList);
        if (rsuh == null) {
            connect();
        }
    }

}
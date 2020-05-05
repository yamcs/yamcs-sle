package org.yamcs.sle;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.sle.RafServiceUserHandler;
import org.yamcs.sle.RafSleMonitor;
import org.yamcs.tctm.ccsds.UdpTmFrameLink;
import org.yamcs.sle.Constants.DeliveryMode;

/**
 * Receives TM frames via SLE. The Virtual Channel configuration is identical with the configuration of
 * {@link UdpTmFrameLink}.
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
 * <td>deliveryMode</td>
 * <td>one of rtnTimelyOnline, rtnCompleteOnline</td>
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
public class TmSleLink extends AbstractTmSleLink {
    RafSleMonitor sleMonitor = new MyMonitor();
    
    public TmSleLink(String instance, String name, YConfiguration config) throws ConfigurationException {
        super(instance, name, config, config.getEnum("deliveryMode", DeliveryMode.class));
        reconnectionIntervalSec = config.getInt("reconnectionIntervalSec", 30);
    }

    @Override
    protected void doStart() {
        setupSystemParameters();
        if (!isDisabled()) {
            connect();
        }
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

    protected void sleStart() {
        rsuh.start().handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to start: " + t.getMessage());
                return null;
            }
            return null;
        });
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
        connect();
    }


    @Override
    public void onEndOfData() {
        eventProducer.sendInfo("SLE end of data received");
    }
}

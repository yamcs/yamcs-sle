package org.yamcs.sle;

import java.util.concurrent.CompletableFuture;

import org.yamcs.ConfigurationException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.Spec.OptionType;
import org.yamcs.tctm.ccsds.UdpTmFrameLink;

import org.yamcs.jsle.Constants.DeliveryMode;
import org.yamcs.jsle.RacfSleMonitor;
import org.yamcs.jsle.user.RafServiceUserHandler;
import org.yamcs.jsle.user.RcfServiceUserHandler;

/**
 * Receives TM frames via SLE. The Virtual Channel configuration is identical with the configuration of
 * {@link UdpTmFrameLink}.
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
 * <tr>
 * <td>service</td>
 * <td>One of RAF (return all frames) or RCF (return channel frames).</td>
 * </tr>
 * <tr>
 * <td>rcfTfVersion</td>
 * <td>Specifies the requested frame version number (0=TM, 1=AOS, 12=USLP). If this option is not used, the frame
 * version number will be derived from the frameType. If specfied, no validation is performed but sent as configured to
 * the SLE provider.</td>
 * </tr>
 * <tr>
 * <tr>
 * <td>rcfSpacecraftId</td>
 * <td>Specifies the number sent as part of the RCF request to the SLE provider. If not specified the spacecraftId will
 * be used.</td>
 * </tr>
 * <tr>
 * <td>rcfVcId</td>
 * <td>Specifies the virtual channel sent as part of the RCF request. If not specified, or if negative, the request will
 * be sent for all VCs</td>
 * </tr>
 * <tr>
 * <tr>
 * <td>deliveryMode</td>
 * <td>one of timely, or complete</td>
 * </tr>
 * <tr>
 * 
 * </table>
 */
public class TmSleLink extends AbstractTmSleLink {
    RacfSleMonitor sleMonitor = new MyMonitor();

    @Override
    public Spec getDefaultSpec() {
        var spec = super.getDefaultSpec();
        spec.addOption("deliveryMode", OptionType.STRING).withRequired(true).withChoices("timely", "complete");
        return spec;
    }

    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config, getDeliveryMode(config));
    }

    private DeliveryMode getDeliveryMode(YConfiguration config) {
        String dm = config.getString("deliveryMode");
        if ("timely".equalsIgnoreCase(dm)) {
            return DeliveryMode.rtnTimelyOnline;
        } else if ("complete".equalsIgnoreCase(dm)) {
            return DeliveryMode.rtnCompleteOnline;
        } else {
            throw new ConfigurationException(
                    "Invalid value '" + dm + "' for deliverMode. Please use 'timely' or 'complete'");
        }
    }

    @Override
    protected void doStart() {
        if (!isDisabled()) {
            connectAndBind(startSleOnEnable);
        }
        notifyStarted();

        addAction(startAction);
        addAction(stopAction);

        startAction.setEnabled(false);
        stopAction.setEnabled(false);
    }

    @Override
    protected void doStop() {
        if (rsuh != null) {
            Utils.sleStop(rsuh, sconf, eventProducer);
            rsuh = null;
        }
        notifyStopped();
    }

    protected void sleStart() {
        requestedState = org.yamcs.jsle.State.ACTIVE;

        CompletableFuture<Void> cf;
        if (gvcid == null) {
            cf = ((RafServiceUserHandler) rsuh).start();
        } else {
            cf = ((RcfServiceUserHandler) rsuh).start(gvcid);
        }
        cf.handle((v, t) -> {
            if (t != null) {
                eventProducer.sendWarning("Failed to start: " + t.getMessage());
                return null;
            }
            log.debug("Successfully started the service");
            rsuh.schedulePeriodicStatusReport(10);
            return null;
        });
    }

    @Override
    protected void doDisable() {
        requestedState = org.yamcs.jsle.State.UNBOUND;
        startAction.setEnabled(false);
        stopAction.setEnabled(false);
        if (rsuh != null) {
            Utils.sleStop(rsuh, sconf, eventProducer);
            rsuh = null;
        }
        eventProducer.sendInfo("SLE link disabled");
    }

    @Override
    protected void doEnable() throws Exception {
        connectAndBind(startSleOnEnable);
        eventProducer.sendInfo("SLE link enabled");
    }

    @Override
    public void onEndOfData() {
        eventProducer.sendInfo("SLE end of data received");
    }
}

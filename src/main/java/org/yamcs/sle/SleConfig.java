package org.yamcs.sle;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.sle.Constants.UnbindReason;
import org.yamcs.sle.Isp1Handler.HeartbeatSettings;
import org.yamcs.sle.user.SleAttributes;
import org.yamcs.utils.StringConverter;

public class SleConfig {
    String host;
    int port;
    Isp1Authentication auth;
    int versionNumber;
    AuthLevel authLevel;
    SleAttributes attr;
    HeartbeatSettings hbSettings = new HeartbeatSettings();
    int tmlMaxLength = 300 * 1024;

    // the reason to send in the unbind call
    UnbindReason unbindReason;

    // how long to wait for a graceful shutdown
    long maxShutdownDelayMillis;

    // how soon should reconnect in case the connection to the SLE provider is lost
    // if negative, do not reconnect
    int reconnectionIntervalSec;

    public SleConfig(YConfiguration config, String type) {
        // tconfig is everything service specific (i.e. under the cltu: in the config file)
        YConfiguration tconfig = config.getConfig(type);

        host = tconfig.getString("host");
        port = tconfig.getInt("port");
        String responderPortId;

        responderPortId = tconfig.getString("responderPortId", config.getString("responderPortId"));
        this.unbindReason = tconfig.getEnum("unbindReason", UnbindReason.class,
                config.getEnum("unbindReason", UnbindReason.class, UnbindReason.END));

        this.maxShutdownDelayMillis = 1000 * tconfig.getLong("maxShutdownDelayMillis",
                config.getLong("maxShutdownDelaySec", 5));

        this.reconnectionIntervalSec = tconfig.getInt("reconnectionIntervalSec",
                config.getInt("reconnectionIntervalSec", 30));

        String initiatorId = config.getString("initiatorId");
        auth = getAuthentication(config);
        authLevel = config.getEnum("authLevel", AuthLevel.class, AuthLevel.BIND);

        long authDelay = 1000l * tconfig.getInt("authenticationDelay", config.getInt("authenticationDelay",
                Isp1Authentication.DEFAULT_MAX_DELTA_RCV_TIME_SEC));
        auth.setMaxDeltaRcvTime(authDelay);
        
        versionNumber = config.getInt("versionNumber", 5);

        String serviceInstance = config.getSubString(type, "serviceInstance");
        attr = new SleAttributes(responderPortId, initiatorId, serviceInstance);

        // these three settings are not used since we are always the initiator. If ever implementing a SLE provider,
        // they may be useful.
        // hbSettings.minHeartbeatInterval = config.getInt("minHeartbeatInterval", hbSettings.minHeartbeatInterval);
        // hbSettings.maxHeartbeatDeadFactor = config.getInt("maxHeartbeatDeadFactor",
        // hbSettings.maxHeartbeatDeadFactor);
        // hbSettings.authenticationTimeout = config.getInt("authenticationTimeout", hbSettings.authenticationTimeout);

        hbSettings.heartbeatInterval = config.getInt("heartbeatInterval", hbSettings.heartbeatInterval);
        hbSettings.heartbeatDeadFactor = config.getInt("heartbeatDeadFactor", hbSettings.heartbeatDeadFactor);

        tmlMaxLength = config.getInt("  tmlMaxLength", tmlMaxLength);
    }

    static Isp1Authentication getAuthentication(YConfiguration c) {
        String myUsername = c.getString("myUsername");
        String peerUsername = c.getString("peerUsername");
        String hashAlgo = c.getString("hashAlgorithm", "SHA-256");
        if (!"SHA-1".equalsIgnoreCase(hashAlgo) && !"SHA-256".equalsIgnoreCase(hashAlgo)) {
            throw new ConfigurationException(
                    "Invalid hash algorithm '" + hashAlgo + "' specified. Supported are SHA-1 and SHA-256");
        }
        byte[] myPass = StringConverter.hexStringToArray(c.getString("myPassword"));
        byte[] peerPass = StringConverter.hexStringToArray(c.getString("peerPassword"));
        Isp1Authentication auth = new Isp1Authentication(myUsername, myPass, peerUsername, peerPass, hashAlgo);

        return auth;
    }
}

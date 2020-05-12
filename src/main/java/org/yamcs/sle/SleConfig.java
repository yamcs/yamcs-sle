package org.yamcs.sle;

import org.yamcs.sle.Isp1Authentication;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
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
    int tmlMaxLength = 300*1024;
    
    public SleConfig(YConfiguration config, String type) {
        host = config.getSubString(type, "host");
        port = config.getInt(type, "port");

        String responderPortId = config.getString("responderPortId");
        String initiatorId = config.getString("initiatorId");
        auth = getAuthentication(config);
        authLevel = config.getEnum("authLevel", AuthLevel.class);
        versionNumber = config.getInt("versionNumber", 5);
        
        String serviceInstance = config.getSubString(type, "serviceInstance");
        attr = new SleAttributes(responderPortId, initiatorId, serviceInstance);
        
        //these three settings are not used since we are always the initiator. If ever implementing a SLE provider, they may be useful.
        //hbSettings.minHeartbeatInterval = config.getInt("minHeartbeatInterval", hbSettings.minHeartbeatInterval);
        //hbSettings.maxHeartbeatDeadFactor = config.getInt("maxHeartbeatDeadFactor", hbSettings.maxHeartbeatDeadFactor);
        //hbSettings.authenticationTimeout = config.getInt("authenticationTimeout", hbSettings.authenticationTimeout);
        
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
        return new Isp1Authentication(myUsername, myPass, peerUsername, peerPass, hashAlgo);
    }
}

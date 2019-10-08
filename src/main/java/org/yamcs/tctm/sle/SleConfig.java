package org.yamcs.tctm.sle;

import org.yamcs.sle.Isp1Authentication;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.sle.AbstractServiceUserHandler.AuthLevel;
import org.yamcs.sle.Constants.DeliveryMode;
import org.yamcs.utils.StringConverter;

public class SleConfig {
    String host;
    int port;
    Isp1Authentication auth;
    DeliveryMode deliveryMode;
    String responderPortId;
    String initiatorId;
    int versionNumber;
    AuthLevel authLevel;
    int serviceInstanceNumber;

    public SleConfig(YConfiguration config) {
        host = config.getString("host");
        port = config.getInt("port");

        responderPortId = config.getString("responderPortId");
        initiatorId = config.getString("initiatorId");
        deliveryMode = config.getEnum("deliveryMode", DeliveryMode.class);
        auth = getAuthentication(config);
        authLevel = config.getEnum("authLevel", AuthLevel.class);
        versionNumber = config.getInt("versionNumber", 5);
        serviceInstanceNumber = config.getInt("serviceInstanceNumber", 1);

        
    }
    
    static Isp1Authentication getAuthentication(YConfiguration c) {
        String myUsername = c.getString("myUsername");
        String peerUsername = c.getString("peerUsername");
        String hashAlgo = c.getString("hashAlgorithm", "SHA-256");
        if (!"SHA-1".equalsIgnoreCase(hashAlgo) && !"SHA-256".equalsIgnoreCase(hashAlgo)) {
            throw new ConfigurationException(
                    "Invalid hash algorithm '" + hashAlgo + "' specified. Supported are SHA-1 and SHA-256");
        }

        YConfiguration sec = YConfiguration.getConfiguration("security");
        YConfiguration slesec = sec.getConfig("SLE");
        byte[] myPass = StringConverter.hexStringToArray(slesec.getString(myUsername));
        byte[] peerPass = StringConverter.hexStringToArray(slesec.getString(peerUsername));
        return new Isp1Authentication(myUsername, myPass, peerUsername, peerPass, hashAlgo);
    }
}

Configuration
=====================

In order to reduce the number of configuration options in the yamcs instance configuration file (``yamcs.<instance>.yaml``), the general SLE properties are specified in a separate file ``sle.yaml``.

An example of such file is here below:

.. code-block:: yaml

    CommonSettings: &CommonSettings
        hashAlgorithm: "SHA-1"
        authLevel: BIND
        versionNumber: 4
        myUsername: "user01"
        myPassword:  01020304ABCD
        initiatorId: "user01"
        responderPortId: "ABC"
        heartbeatInterval: 30
        heartbeatDeadFactor: 3

    Providers:
        GS1:
            <<: *CommonSettings
            peerUsername: "prov-user"
            peerPassword: AB0102030405060708090a0b0c0d0e0f
        
            cltu:
                host: 172.16.1.113
                port: 63800
                serviceInstance: "sagr=9999.spack=ABC-PERM.fsl-fg=1.cltu=cltu1"
    
            raf-onlt:
                host: 172.16.1.113
                port: 63900
                serviceInstance: "sagr=9999.spack=ABC-PERM.rsl-fg=1.raf=onlt1"
    
            raf-onlc:
                host: 172.16.1.113
                port: 63901
                serviceInstance: "sagr=9999.spack=ABC-PERM.rsl-fg=1.raf=onlc1"


The important section is ``Providers`` which contains a map with each SLE provider (e.g. ground station). This contains a section with general settings followed by a specific cltu, raf-ontl and raf-onlc sections containing connection parameters for the CLTU service, RAF online timely respectively RAF online complete services.

In the example above the CommonSettings block is included into the SMILE3 provider block using the yaml node achor and reference feature. This feature (pure yaml functionality, not specific to SLE) allows in this instance grouping common settings for multiple ground stations into one definition.

General Options
---------------

hashAlgotihm  (string)
    Either ``SHA-1`` or ``SHA-256``. The hashAlgorithm is effectively passed to the `MessageDigest.getInstance(hashAlgorithm) <https://docs.oracle.com/javase/8/docs/api/java/security/MessageDigest.html#getInstance-java.lang.String>`_ method in Java. Only SHA-1 has been tested since the SHA-256 is not supported (as of 2020) by the ESA SLE software.
    
authLevel (string)
    One of: ``ALL``, ``BIND`` or ``NONE``.
    The SLE messages can be optionally authenticated by inserting a ``invoker-credentials`` token in the SLE message. This option specifies which messages sent by us are authenticated (i.e. contain this token). It also specifies which of the peer messages are expected to be authenticated.
    
    **Important:** the SLE security mechanism does not prevent man-in-the-middle attacks and other type of security attacks and therefore it is advisable to protect the transport at TCP level by other means (e.g. VPN).
    Please read the chapter 8 Security Aspects of the SLE Forward CLTU Transfer Service in the CCSDS specification for details.

versionNumber (integer)
    2, 3 or 4. This is the version number that is being passed in the BIND call. Note that there is no code to handle specifically any version but the versions 2-4 are of SLE are belived to be compatible as far as the user (i.e. Yamcs) is concerned; the differences are mostly to do with new options being added in the newer versions. Those options are currently not accessible from the Yamcs SLE link.

myUsername (string)
    the username that is used to build the credentials token part of ISP1 authentication if the authLevel is ALL or BIND.
    
myPassword (hexadecimal string)
     used together with the myUsername for the ISP1 authentication.

peerUsername (string)
    the username of the peer - it is used to verify the peer credential tokens. Depending on the authLevel, all messages, the bind return or no message will be authenticated.

peerPassword (hexadecimal string)
    used together with the username for verifying the peer suplied credential token.

initiatorId (string)
    this property species the value of the ``initiator-identifier`` parameter passed as part of the BIND SLE message. 

responderPortId (string)
    this property species the value of the ``responder-port-identifier`` parameter passed as part of the BIND SLE message.
 
heartbeatInterval (integer number of seconds)
    this property specifies the value proposed to the peer for the ISP1 heartbeat interval. The SLE provide will check this against its accepted range and it will close the connection if the value sent by us is not within the accepted range.
    
heartbeatDeadFactor (integer)
    defines the number of heartbeat intervals when no message has been received from the peer, after which a TCP connection is considered dead and is closed.
    
Service specific options
------------------------

host (string)
    the hostname or IP address to connect to.

port (integer)
    the port number to connect to.
        
        
serviceInstance (string)
    used (after transformation to binary form) as ``service-instance-identifier`` parameter in the SLE BIND call to identify the service requested to the provider. It is a series of ``sia=value`` separated by dots where sia is a service identifier attribute.
    
    Please ask your SLE provider for the value of this parameter. 

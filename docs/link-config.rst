Link Configuration
==================

After having specified the SLE properties in ``sle.yaml`` a link configuration in ``yamcs.<instance>.yaml`` looks as follows:

.. code-block:: yaml

    dataLinks:
        - name: SLE_IN
          class: org.yamcs.sle.TmSleLink
          sleProvider: GS1
          deliveryMode: timely
          service: RAF
          <frame processing specific parameters>
            


The following options can be specified:

class(string)
    - ``org.yamcs.sle.TmSleink`` for online RAF service. This link binds and starts a SLE session as soon as it is enabled. If the connection goes down it will continually try to re-estabilish it.
    - ``org.yamcs.sle.TcSleLink`` for CLTU service. Similarly to the TmSleLink, this link tries to bind and start a SLE session for as long as it is enabled.
    - ``org.yamcs.sle.OfflineTmSleLink`` for offline RAF service. This link does not retrieve any data by itself. Instead it expectes requests to be made using the  :doc:`request-offline-data` REST API. As long as it has retrieval requests in the queue, it keeps a connection open and bound to the sLE provider and it starts/stops SEL sessions for each retrieval request. After all the retrievals have been finished, it unbinds and closes the connection to the SLE provider. If a request fails for whatever reason (for example could not connect to SLE provider), it does not reattempt to execute it.
    
 
sleProvider(string)
    the name of a provider defined in ``sle.yaml`` file.

service(string)
    used the TmSleLink or OfflineTmSleLink to chose between ``RAF`` and ``RCF`` SLE services. If the RCF service is used, the request sent with the SLE START includes the triplet (TransferFrameVersionNumber, SpacecraftId, VirtualChannelId). The values for the TransferFrameVersionNumber and SpacecraftId parameters are normally derived from the frame processing configuration but can be overriden by the options below. Overriding them will most likely result in an invalid configuration. The value of the VirtualChannelId is by default -1 meaning all VCs are requested but can be restricted to only one VC with the option below.
    
    Default: RAF
    
rcfTfVersion(integer)
    used for TmSleLink or OfflineTmSleLink if the ``service`` is RCF. It can override the Transfer Frame Version Number which is otherwise derived from the ``frameType`` parameter part of the frame processing configuration.

rcfSpacecraftId(integer)
    used for TmSleLink or OfflineTmSleLink if the ``service`` is RCF. It can override the Spacraft Id which is otherwise the one specified ``spacraftId`` parameter part of the frame processing configuration.

rcfVcId
    used for TmSleLink or OfflineTmSleLink if the ``service`` is RCF. The Virtual Channel requested via RCF. By default it is -1 meaning all Virtual Channels for the defined spacraft. There is validation that this virtual chanel is defined in the ``virtualChannels`` parameter part of the frame processing configuration.

deliveryMode(string)
     **used and required for the TmSleLink** to select between complete and timely modes. It can have one of the following values:
        - ``compete``
        - ``timely``
        

reconnectionIntervalSec(integer)
    used for the online RAF service (TmSleLink) and CLTU service (TcSleLink) to select the reconnection interval. If the connection to the SLE provider breaks, Yamcs will attempt to reconnect after that many seconds.
    
    Default: 30 seconds
   
   
The rest of the parameters in the link configuration are general frame processing parameters as specified in the Yamcs Manual -> Data Links -> Frame Processing.
 

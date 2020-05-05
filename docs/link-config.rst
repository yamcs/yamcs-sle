Link Configuration
==================

After having specified the SLE properties in ``sle.yaml`` a link configuration in ``yamcs.<instance>.yaml`` looks as follows:

.. code-block:: yaml

    dataLinks:
        - name: SLE_IN
          class: org.yamcs.sle.TmSleLink
          args:
              sleProvider: GS1
              deliveryMode: rtnTimelyOnline
              <frame processing specific parameters>
            


The following options can be specified:

class(string)
    - ``org.yamcs.sle.TmSleink`` for online RAF service. This link binds and starts a SLE session as soon as it is enabled. If the connection goes down it will continually try to re-estabilish it.
    - ``org.yamcs.sle.TcSleLink`` for CLTU service. Similarly to the TmSleLink, this link tries to bind and start a SLE session for as long as it is enabled.
    - ``org.yamcs.sle.OfflineTmSleLink`` for offline RAF service. This link does not retrieve any data by itself. Instead it expectes requests to be made using the  :doc:`request-offline-data` REST API. As long as it has retrieval requests in the queue, it keeps a connection open and bound to the sLE provider and it starts/stops SEL sessions for each retrieval request. After all the retrievals have been finished, it unbinds and closes the connection to the SLE provider. If a request fails for whatever reason (for example could not connect to SLE provider), it does not reattempt to execute it.
    
 
sleProvider(string)
    the name of a provider defined in ``sle.yaml`` file.

deliveryMode(string)
    only used for the online RAF service (TmSleLink) to select between complete and timely modes. It can have one of the following values:
        - ``rtnCompleteOnline``
        - ``rtnTimelyOnline``

reconnectionIntervalSec(integer)
    used for the online RAF service (TmSleLink) and CLTU service (TcSleLink) to select the reconnection interval. If the connection to the SLE provider breaks, Yamcs will attempt to reconnect after that many seconds.
    
    Default: 30 seconds
   
   
The rest of the parameters in the link configuration are general frame processing parameters as specified in the Yamcs Manual -> Data Links -> Frame Processing.
 

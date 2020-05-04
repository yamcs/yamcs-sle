Link Configuration
==================

After having specified the SLE properties in ``sle.yaml`` a link configuration in ``yamcs.<instance>.yaml`` looks as follows:

.. code-block:: yaml

    dataLinks:
        - name: SLE_IN
          class: org.yamcs.sle.TmFrameLink
          args:
              sleProvider: GS1
              deliveryMode: rtnTimelyOnline
              <frame processing specific parameters>
            


The following options can be specified:

class(string)
    - ``org.yamcs.sle.TmFrameLink`` for RAF service 
    - ``org.yamcs.sle.TcFrameLink`` for CLTU service. 

 
sleProvider(string)
    the name of a provider defined in ``sle.yaml`` file.

deliveryMode(string)
    only used for the RAF service (TmFrameLink) to select between online complete and online timely modes. It can have one of the following values:
        - ``rtnTimelyOnline``
        - ``rtnTimelyComplete``
    
    
The rest of the parameter in the link configuration are general frame processing parameters as specified in the Yamcs Manual -> Data Links -> Frame Processing.

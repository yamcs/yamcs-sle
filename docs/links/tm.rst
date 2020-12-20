TM Link
=======

Online RAF service. This link binds and starts a SLE session as soon as it is enabled. If the connection goes down it will continually try to re-establish it.


Usage
-----

Specify SLE properties in ``etc/sle.yaml``, and add a link entry to ``etc/yamcs.[instance].yaml``:

.. code-block:: yaml

    dataLinks:
        - name: SLE_IN
          class: org.yamcs.sle.TmSleLink
          service: RAF
          deliveryMode: timely
          


Options
-------

sleProvider (string)
    **Required.** The name of a provider defined in the ``etc/sle.yaml`` configuration file.

service (string)
     One of: ``RAF`` or ``RCF``. Default RAF.
     
     Depending on this and the ``deliveryMode``, one of the entries raf-ontl, raf-onlc, rcf-ontl, rcf-onlc from sle.yaml will be used.
     
deliveryMode (string)
    **Required.** One of: ``complete`` or ``timely``.

.. include:: _includes/tm-options.rst
.. include:: _includes/reconnect-options.rst

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.

rcfTfVersion (number)
    Can be used for the RCF service to specify the transfer frame version. By default, it is configured based on the setting ``frameType`` specified as part of frame processing parameters.
    
rcfSpacecraftId (number)
    Can be used for the RCF service to specify the spacecraft id. By default, it is configured based on the setting ``spacecraftId``  specified as part of frame processing parameters.

rcfVcId: -1
    The Virtual Channel requrested via RCF. -1 means all virtual channels for the master channel.

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
          sleProvider: GS1
          deliveryMode: timely
          startSleOnEnable: false
          # other options



Options
-------

sleProvider (string)
    **Required.** The name of a provider defined in the ``etc/sle.yaml`` configuration file.

service (string)
     One of: ``RAF`` or ``RCF``. Default RAF.
     
deliveryMode (string)
    **Required.** One of: ``complete`` or ``timely``.

startSleOnEnable (boolean)
    Whether the SLE session should automatically be STARTed when the link is enabled. If ``false``, enabling the link will only BIND it. Default: ``true``

.. include:: _includes/tm-options.rst
.. include:: _includes/reconnect-options.rst

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.

privateAnnotationParameter (string)
   If specified, it is the name of a fully qualified binary parameter in the MDB. The parameter will be updated with the private annotation data received via SLE. 
   The data is provided as binary content of the data is specific to each provider/mission.
   The parameter must exist in the MDB, otherwise Yamcs will not start.
   The parameter is timestamped with the Earth Reception Time received from the SLE provider.
   

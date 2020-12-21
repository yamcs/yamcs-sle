Offline TM Link
===============

Offline RAF/RCF service. This link does not retrieve any data by itself. Instead it expects requests to be made using the :doc:`../http-api/request-offline-data` HTTP API. As long as it has retrieval requests in the queue, it keeps a connection open and bound to the SLE provider and it starts/stops SLE sessions for each retrieval request. After all the retrievals have been finished, it unbinds and closes the connection to the SLE provider. If a request fails for whatever reason (for example could not connect to SLE provider), it does not reattempt to execute it.


Usage
-----

Specify SLE properties in ``etc/sle.yaml``, and add a link entry to ``etc/yamcs.[instance].yaml``:

.. code-block:: yaml

    dataLinks:
        - name: SLE_OFFLINE_IN
          class: org.yamcs.sle.OfflineTmSleLink
          sleProvider: GS1
          # other options


Options
-------

sleProvider (string)
    **Required.** The name of a provider defined in the ``etc/sle.yaml`` configuration file.

.. include:: _includes/tm-options.rst

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.

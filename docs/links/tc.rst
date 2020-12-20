TC Link
=======

CLTU service. Like with the :doc:`tm`, this link tries to bind and start a SLE session for as long as it is enabled.


Usage
-----

Specify SLE properties in ``etc/sle.yaml``, and add a link entry to ``etc/yamcs.[instance].yaml``:

.. code-block:: yaml

    dataLinks:
        - name: SLE_OUT
          class: org.yamcs.sle.TcSleLink
          sleProvider: GS1


Options
-------

sleProvider (string)
    **Required.** The name of a provider defined in the ``etc/sle.yaml`` configuration file.

.. include:: _includes/reconnect-options.rst

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.

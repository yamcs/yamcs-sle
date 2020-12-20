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
          # options


Options
-------

sleProvider (string)
    **Required.** The name of a provider defined in the ``etc/sle.yaml`` configuration file.

deliveryMode (string)
    **Required.** One of: ``complete`` or ``timely``.

.. include:: _includes/tm-options.rst
.. include:: _includes/reconnect-options.rst

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.

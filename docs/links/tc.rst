TC Link
=======

CLTU service. Like :doc:`tm`, this link tries to bind and start a SLE session for as long as it is enabled.


Usage
-----

Specify SLE properties in ``etc/sle.yaml``, and add a link entry to ``etc/yamcs.[instance].yaml``:

.. code-block:: yaml

    dataLinks:
        - name: SLE_OUT
          class: org.yamcs.sle.TcSleLink
          sleProvider: GS1
          startSleOnEnable: false
          # other options

Queing behaviour
----------------

This link does not queue any command frames internally. It expects the SLE provider to queue up to ``maxPandingFrames`` command frames. This is required in order to be able to properly fill the uplink, otherwise the provider may not get the next frame in time and will need to fill in some dummy data (according to the PLOP in effect).

When the link is enabled and the SLE connection is estabilished, the link performs the following steps in a loop:

1. Get a command frame from the multiplexer, waiting if necessary until one becomes available.
2. If the uplink is available and the number of commands in the provider does not exceed the maxPandingFrames send the command to the provider.
3. Otherwise wait for ``waitForUplinkMsec`` milliseconds. If the condition is still not met, drop the frame. If the frame has the by-pass flag set (BD frame), all the commands inside (which at this time is only one since Yamcs does not send BD frames with multiple commands) will be negatively acknowledged (otherwise the COP1 will take care of the acknowledgments).

The multiplexer will provide commands from different virtual channels and the virtual channel sub-links (one per virtual channel) will have their own queueing in operation. If COP1 is used, then the COP1 specific queueing settings will be used (taking into account the overall number of un-acknowledged frames), otherwise a simple queue size is used. The :yamcs-manual:`links/ccsds-frame-processing` provides more details.

Thus there can be a number of commands pending transmission, some in the specific virtual channel sub-links, some in the SLE Provider (ground-station). The ``SLE_REQ`` command history attribute will be set for the commands that have been sent to the SLE Provider.

Note that if the SLE TC link is disabled, the multiplexer will not be queried for new frames, so the commands may queue in the sub-links, indefinitely. This will only happen however, if the main SLE TC link is disabled and the virtual channel sub-links enabled.


Options
-------

sleProvider (string)
    **Required.** The name of a provider defined in the ``etc/sle.yaml`` configuration file.

waitForUplinkMsec (integer)
    If a command is received and the uplink is not available, wait this number of milliseconds for the uplink to become available. If 0 or negative, then drop the command immediately. Default: ``5000`` (5 seconds)

maxPendingFrames:
    Maximum number of pending frames in the SLE provider (waiting or being uplinked). If this number is reached we start rejecting new frames but only after waiting waitForUplinkMsec before each frame. Default: ``20``.

startSleOnEnable (boolean)
    Whether the SLE session should automatically be STARTed when the link is enabled. If ``false``, enabling the link will only BIND it. Default: ``true``

.. include:: _includes/reconnect-options.rst

.. note::
    Other available link options are general frame processing parameters as specified at :yamcs-manual:`links/ccsds-frame-processing`.

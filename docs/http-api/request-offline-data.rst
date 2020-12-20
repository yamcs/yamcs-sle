Request Offline Data
====================

This request can be used to obtain offline data from an SLE provider. The link has to be of type ``OfflineTmSleLink``.

.. rubric:: URI Template

.. code-block::

    POST /api/sle/links/{instance}/{linkName}:requestOfflineData
    

.. rubric:: Body Parameters

``start``
    **Required.** Start of the retrieval interval. Specify a date string in ISO 8601 format.

``stop``
    **Required.** End of the retrieval interval. Specify a date string in ISO 8601 format.


.. rubric:: Description

``start`` and ``stop`` will be passed as parameters to the RAF-START SLE request. The SLE provider will deliver all frames having their Earth Reception Time (ERT) in the [start, stop] time interval, both ends are inclusive. SLE insists that start is strictly smaller than stop so start=stop will not be accepted.

The time passed via API is at nanosecond resolution whereas the time passed in the RAF-START SLE request is at microsecond or picosecond resolution (depending on the SLE version used).

Note about the leap seconds: the time passed via the API is according to the `Timestamp <https://github.com/protocolbuffers/protobuf/blob/master/src/google/protobuf/timestamp.proto>`_ protobuf message and it is passed "unsmeared" to the SLE request. This is different from the normal requests to Yamcs where the time is transformed into internal Yamcs time (but at millisecond resolution!) by performing reverse smearing around the leap seconds.
This means that it is not possible to specify a retrieval that starts or stops on a leap second. For example "2016-12-31T23:59:60Z" will be effectively translated into "2017-01-01T00:00:00Z".


.. rubric:: Example
.. code-block:: json
      
   {
       "start": "2020-04-05T16:00:00Z", 
       "stop": "2020-05-05T18:50:00Z"
   }

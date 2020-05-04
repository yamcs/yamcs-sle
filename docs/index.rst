Yamcs SLE
===============

This plugin extends Yamcs with links to connect via CCSDS SLE (Space Link Extension) protocol to SLE enabled ground stations. These are typically the ground stations of national space agencies.



The supported services are:
 * CCSDS Return All Frames (RAF) specified in `CCSDS 911.1-B-4 <https://public.ccsds.org/Pubs/911x1b4.pdf>`_.
 * CCSDS Forward CTU (FLCTU) specified in `CCSDS 912.1-B-4  <https://public.ccsds.org/Pubs/912x1b4.pdf>`_.

The two services are supported by the SLE Internet Protocol for transfer services (ISP1) specified in `CCSDS 913.1-B-2 <https://public.ccsds.org/Pubs/913x1b2.pdf>`
 

The RAF service is further divided into:
 * RAF online timely - used for retrieval of frames where there is a guarantee timeliness of data - if the receiver of the data cannot process the data fast enough (or the network link towards the receiver is too slow), the provider will drop data.
 * RAF online complete - used for retrieval of frames where the receiver wants to receive complete data at the expense of timeliness. The provider will buffer the data if the receiver is too slow.


Maintainer
----------

Space Applications Services


License
-------

AGPL


.. toctree::

  configuration
  link-config

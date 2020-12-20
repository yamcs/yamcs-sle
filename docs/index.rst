Yamcs: SLE Plugin
=================

This plugin extends Yamcs with links to connect via the CCSDS SLE (Space Link Extension) protocol to SLE-enabled ground stations. These are typically the ground stations of national space agencies.

The supported services are:

* CCSDS Return All Frames (RAF) specified in `CCSDS 911.1-B-4 <https://public.ccsds.org/Pubs/911x1b4.pdf>`_.
* CCSDS Forward CLTU (FCLTU) specified in `CCSDS 912.1-B-4 <https://public.ccsds.org/Pubs/912x1b4.pdf>`_.

The two services are supported by the SLE Internet Protocol for transfer services (ISP1) specified in `CCSDS 913.1-B-2 <https://public.ccsds.org/Pubs/913x1b2.pdf>`_.
 

The RAF service is further divided into:

* **RAF online timely:** used for retrieval of frames where there is a guaranteed timeliness of data - if the receiver of the data cannot process the data fast enough (or the network link towards the receiver is too slow), the provider will drop data.

* **RAF online complete:** used for retrieval of frames where the receiver wants to receive complete data at the expense of timeliness. The provider will buffer the data if the receiver is too slow.

* **RAF offline:** used for retrieval of frames stored at the provider.

.. rubric:: Usage with Maven

Add the following dependency to your Yamcs Maven project. Replace ``x.y.z`` with the latest version. See https://mvnrepository.com/artifact/org.yamcs/yamcs-sle

.. code-block:: xml

   <dependency>
     <groupId>org.yamcs</groupId>
     <artifactId>yamcs-sle</artifactId>
     <version>x.y.z</version>
   </dependency>


.. toctree::
    :titlesonly:
    :caption: Table of Contents

    provider-configuration
    links/index
    http-api/index

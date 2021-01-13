# Yamcs: SLE Plugin ![Maven Central](https://img.shields.io/maven-central/v/org.yamcs/yamcs-sle.svg?label=release)

This project contains Yamcs Data Links for enabling yamcs to receive data from an SLE provider.
For the moment only FCLTU (Forward CLTU), RAF (Return All Frames) and RCF (Return Channel Frame) are supported.

The plugin is a straightforward implementation of Yamcs data links based on the [jsle](https://github.com/yamcs/jsle) package. The jsle package also contains a SLE to UDP bridge allowing to test the plugin together with a simulator which sends/receives frames via UDP.

## Documentation

https://docs.yamcs.org/yamcs-sle/


## License

Affero GPLv3


## Installation and test

To test it:
- clone the repository
- change the SLE providers in the src/main/yamcs/etc/sle.yaml 
- change the TM/TC frames parameers in the src/main/yamcs/etc/yamcs.sle.yaml, dataLinks section
- run:

```
mvn yamcs:run
```

This will create one instance called sle. Open a web browser connected to http://localhost:8090/, select the sle instance and navigate to the "Links" in the left menu. Also open another window showing the Monitoring -> Events. Disabling/Enabling the link will cause the connection to be closed/established. Once the connection is established, it will immediately perfom the bind SLE operation and if that is successful, the start operation.




![](yamcs-connected-to-sle.png?raw=true)

This project contains Yamcs Data Links for enablign yamcs to receive data from an SLE provider.
For the moment only RAF (Return All Frames) is supported.

## Installation and test
To test it, please follow this procedure (if you want to run in development mode, please see below):
- install [yamcs](https://github.com/yamcs/yamcs/releases) either with rpm, deb or tgz.
```
/home/test$
tar xzf yamcs-4.9.3.tar.gz
```

- clone the [yamcs-sle](https://github.com/yamcs/yamcs-sle) repository into a
```
/home/test$
   git clone https://github.com/yamcs/yamcs-sle
````

- compile the project with maven
```
cd yamcs-sle
mvn install
```


- copy the resulting jars into the yamcs lib/ext directory
```
cp target/*jar ~/yamcs-4.9.3/lib/ext
```

- copy the sample configuration files into the yamcs etc directoy
```
cp etc/* ~/yamcs-4.9.3/etc/
```

- copy the sample mission database (that defines the telemetry structure) into the yamcs mdb directory
```
cp mdb/* ~/yamcs-4.9.3/mdb/
```
- edit the SLE configuration in etc/yamcs.sle.yamcs, data links section as well as the SLE username/password in the etc/

- start yamcs

```
cd ~/yamcs-4.9.3
bin/yamcsd
```


This will create one instance called sle. Open a webbrowser connected to http://localhost:8090/, select the sle instance and navigate to the "Links" in the left menu. Also open another window showing the Monitoring -> Events. Disabling/Enabling the link will cause the connection to be closed/estabilished. Once the connection is estabilished, it will immediately perfom the bind SLE operation and if that is successful, the start.



## TODO:
- make a REST interface to allow controling bind/start operations. This will allow also specifying start/stop times for retrieving offline data.
- send parameters of the SLE as yamcs parameters to allow monitoring the status in the normal displays.
- implement commanding via CLTU service
- implement the RCF (this should be very simple)

## Development mode
To run in development mode (both yamcs and yamcs-sle), please clone both yamcs and yamcs-sle in the same directory (e.g. /home/test/git/yamcs and /home/test/git/yamcs-sle).

After compiling yamcs with make (or mvn install), and yamcs-sle with mvn install, a yamcs server can be started from the live directory by running the script dev.sh in the yamcs-sle repository.


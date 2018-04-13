# Prototype Galil Controller HCD Implementation

This project implements an HCD (Hardware Control Daemon) that talks to a Galil controller using 
the TMT Common Software ([CSW](https://github.com/tmtsoftware/csw-prod)) APIs. 

## Subprojects

* galil-assembly - an assembly that talks to the Galil HCD
* galil-client - client applications that talk to the Galil assembly or HCD
* galil-commands - defines device-independent commands and a Galil implementation of them
* galil-hcd - an HCD that talks to the Galil hardware
* galil-io - library implementing the communication with the Galil hardware
* galil-repl - a command line client for a Galil device where you can enter Galil commands and see the responses
* galil-simulator - implements a simulator for a Galil device (Only a small subset of Galil commands are simulated)

## Build Instructions

The build is based on sbt and depends on libraries published locally from the 
[csw-prod](https://github.com/tmtsoftware/csw-prod) project.

See [here](https://www.scala-sbt.org/1.0/docs/Setup.html) for instructions on installing sbt.

Before building this project, make sure to checkout and build the csw-prod project. For example:
```
git clone https://github.com/tmtsoftware/csw-prod.git
cd csw-prod
sbt publishLocal stage
```

The following command can be used to build and install the galil-prototype applications from this directory:
```
sbt publishLocal stage
```

This publishes the library jar files in the local [ivy](https://en.wikipedia.org/wiki/Apache_Ivy) repository 
and installs the prototype applications in _./target/universal/stage/bin/_.

## Running the galil-prototype applications

The tests and applications in this project require that the CSW location service cluster and config service are
running. These can be found in _csw-prod/target/universal/stage/bin_.

Make sure also that the necessary environment variables are set. For example:

* Set these environment variables (Replace interface name, IP address and port with your own values):
```bash
export interfaceName=enp0s31f6
export clusterSeeds=192.168.178.77:7777
```
for bash shell, or 
```csh
setenv interfaceName enp0s31f6
setenv clusterSeeds 192.168.178.77:7777
```

for csh or tcsh. The list of available network interfaces can be found using the _ifconfig -a_ command.
If you don't specify the network interface this way, a default will be chosen, which might sometimes not be
the one you expect. 

* Start the location service: 

```bash
csw-cluster-seed --clusterPort 7777
```

* Start the config service:
```bash
csw-config-server --initRepo
```

To run the Galil HCD using an actual Galil device, run the `galil-hcd` command with the options:
```
galil-hcd --local GalilHcd.conf -Dgalil.host=myhost -Dgalil.port=23
```

An example GalilHcd.conf file can be found [here](galil-hcd/src/main/resources/GalilHcd.conf). 
If `--local` is not given, the file would be fetched from the Config Service, if available.

To run using a Galil simulator:
```
galil-simulator
galil-hcd --local GalilHcd.conf
```

## Loading the galil-prototype project in IntelliJ Idea

To load the project in IntelliJ Idea, select *New => Project from Existing Sources...* from the File menu
and then select this directory.

## Running or Debugging the Galil HCD from IntelliJ Idea

To run or debug the Galil HCD from IntelliJ Idea, go to the GalilHcdApp class and select *Run GalilHcdApp*.
Note that this assumes that the location service and config service are running as described above.
If the environment variables are not set, you can configure Idea to use the VM Parameters under 
GalilHcdApp => *Edit Configurations*. 

For example: 
```
VM Parameters: -DinterfaceName=... -DclusterSeeds=host:port
```

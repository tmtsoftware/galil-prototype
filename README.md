# Prototype Galil Controller HCD Implementation

This project implements an HCD (Hardware Control Daemon) that talks to a Galil controller using 
the TMT Common Software ([CSW](https://github.com/tmtsoftware/csw)) APIs. 

## Subprojects

* galil-assembly - an assembly that talks to the Galil HCD
* galil-client - client applications that talk to the Galil assembly or HCD
* galil-commands - defines device-independent commands and a Galil implementation of them
* galil-hcd - an HCD that talks to the Galil hardware
* galil-io - library implementing the communication with the Galil hardware
* galil-repl - a command line client for a Galil device where you can enter Galil commands and see the responses
* galil-simulator - implements a simulator for a Galil device (Only a small subset of Galil commands are simulated)

## Build Instructions

The build is based on sbt and depends on libraries published from the 
[csw](https://github.com/tmtsoftware/csw) project. 

Note: The version of csw used by this project is declared in the variable CSW.Version in the file [project/Libs.scala](project/Libs.scala).
That value may be a Git SHA for the commt that was last tested with this project. If so, use it in place of `$SHA` below.

See [here](https://www.scala-sbt.org/1.0/docs/Setup.html) for instructions on installing sbt.

Before building this project, make sure to checkout and build the csw project. For example:
```
git clone https://github.com/tmtsoftware/csw.git
cd csw
git checkout $SHA         # Value of CSW.Version in project/Libs.scala, if not "0.1-SNAPSHOT"
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
running. These can be started by running `csw-services.sh start` from the `csw` project.

* Start the csw services: 

```
csw-services.sh start
```

To run the Galil HCD using an actual Galil device, run the `galil-hcd` command with the options:
```
galil-hcd --local galil-hcd/src/main/resources/GalilHcd.conf -Dgalil.host=myhost -Dgalil.port=23
```

An example GalilHcd.conf file can be found [here](galil-hcd/src/main/resources/GalilHcd.conf). 
If `--local` is not given, the file would be fetched from the Config Service, if available.

To run using a Galil simulator:
```
galil-simulator
galil-hcd --local galil-hcd/src/main/resources/GalilHcd.conf
```

## Loading the galil-prototype project in IntelliJ Idea

To load the project in IntelliJ Idea, select *New => Project from Existing Sources...* from the File menu
and then select this directory.

## Running or Debugging the Galil HCD from IntelliJ Idea

To run or debug the Galil HCD from IntelliJ Idea, go to the GalilHcdApp class and select *Run GalilHcdApp*.
Note that this assumes that the location service and config service are running as described above.

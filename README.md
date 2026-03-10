# Galil Motion HCD Prototype

A prototype Hardware Control Daemon (HCD) for Galil motion controllers, built with
the TMT Common Software ([CSW](https://github.com/tmtsoftware/csw)) framework.

This is a working prototype for the TMT Alignment and Phasing System (APS)
Instrument Control Software (ICS), implementing the CSW interface to Galil DMC-500 controllers.

## Architecture

The HCD is a **thin orchestrator** — motion algorithms live in embedded DMC programs
on the Galil controller, not in the HCD. The HCD's responsibilities are:

- Load and verify embedded programs on the controller
- Execute programs by name (`XQ`) with dynamic thread allocation
- Monitor controller state via QR binary data records (adaptive polling)
- Publish CSW CurrentState events for Assemblies to observe

The HCD actor architecture consists of a ControllerInterfaceActor (all Galil I/O),
InternalStateActor (central state repository with dual-channel pub/sub),
StatusMonitor (adaptive-rate QR polling), CommandHandlerActor (command dispatch with
per-command CommandWatcherActors), and CurrentStatePublisherActor (CSW event publishing
with reactive state-transition notifications).

Hardware details (motor type, limit switches, position source) are read directly from
the controller during initialization.

## Subprojects

| Module | Description |
|--------|-------------|
| **galil-hcd** | HCD implementation — actor architecture, command handling, state management, embedded program verification |
| **galil-io** | Low-level Galil communication library (TCP, binary QR DataRecord parsing) |
| **galil-assembly** | Assembly that talks to the Galil HCD |
| **galil-client** | Client applications for the Galil assembly or HCD |
| **galil-simulator** | Galil device simulator (subset of commands) |
| **galil-repl** | Interactive command-line client for direct Galil commands |
| **galil-deploy** | Deployment configuration (HostConfig, ContainerCmd) |

## Technology Stack

- **Scala 3** (3.6.4)
- **CSW 6.0.0** (Common Software for TMT)
- **Apache Pekko 1.1.3** (actor framework)
- **sbt 1.10.6** (build system)
- **Java 21** (required)

## Prerequisites

- **Java 21** — `java -version` to verify
- **sbt** — See [sbt setup](https://www.scala-sbt.org/1.0/docs/Setup.html)
- **CSW Services** — Required for running the HCD standalone (not needed for unit tests).
  See [CSW installation](https://tmtsoftware.github.io/csw/apps/csinstallation.html).

## Building

```bash
sbt clean compile
sbt stage
```

After `sbt stage`, start scripts are generated in `./target/universal/stage/bin/`.

## Running the HCD

### With Simulator

The simulator uses `GalilHcdSim.conf` so it registers under a distinct prefix
(`aps.ICS.HCD.GalilMotion.Sim`) and serves the HMI on port 9090.

```bash
# Terminal 1: Start the Galil simulator
sbt "galil-simulator/run"

# Terminal 2: Start CSW services
csw-services start

# Terminal 3: Build and launch the HCD against the simulator
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcdSim.conf \
  -Dgalil.config.path=GalilHcdConfig-Simulator.conf

# Open browser to http://localhost:9090
```

### With Hardware

Hardware instance 1 registers as `aps.ICS.HCD.GalilMotion.1` and serves the
HMI on port 9091 (9090 + controller id).

```bash
# Terminal 1: Start CSW services
csw-services start

# Terminal 2: Build and launch the HCD against hardware
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd.conf \
  -Dgalil.config.path=GalilHcdConfig-Hardware.conf

# Open browser to http://localhost:9091
```

See the [galil-hcd README](galil-hcd/) for configuration details, test instructions, and
command documentation.

## References

- [CSW Documentation](https://tmtsoftware.github.io/csw/6.0.0/)
- [CSW Source](https://github.com/tmtsoftware/csw)
- [CSW Example Components](https://github.com/tmtsoftware/csw-vslice-example)
- [Galil DMC Programming Reference](https://www.galil.com/downloads/manuals-and-data-sheets)
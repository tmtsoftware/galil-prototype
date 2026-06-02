# Galil Motion HCD Prototype

A prototype Hardware Control Daemon (HCD) for Galil motion controllers, built with
the TMT Common Software ([CSW](https://github.com/tmtsoftware/csw)) framework.

This is a working prototype for the TMT Alignment and Phasing System (APS)
Instrument Control Software (ICS), implementing the CSW interface to Galil DMC-500x0 controllers.

## Architecture

The HCD is a **thin orchestrator** — motion algorithms live in embedded DMC programs
on the Galil controller, not in the HCD. The HCD's responsibilities are:

- Load and verify embedded programs on the controller
- Write motion configuration to the controller's embedded variable arrays
- Execute programs by name (`XQ`) with dynamic thread allocation
- Monitor controller state via QR binary data records (adaptive 1 Hz / 10 Hz polling)
- Detect and surface controller-side errors (per-axis via `ae[]`, controller-level via `TC`)
- Manage Faulted state and recovery (connection loss, controller errors, embedded program failures, initialization failures)
- Publish CSW CurrentState events for Assemblies to observe

### Actor Hierarchy

```
GalilHcd (ComponentHandler)
├── InternalStateActor          — central state repository; dual-channel pub/sub
├── ControllerCommandActor      — command TCP connection (XQ, MG, ST, HX, TC at init)
├── ControllerStatusActor       — status TCP connection (QR polling, ae[] reads, AI polling)
├── ControllerConsoleActor      — console TCP connection (informational, hardware-only)
├── CommandHandlerActor         — command dispatch
│   └── CommandWatcherActor     — per-command lifecycle monitor (one per active command)
├── CurrentStatePublisherActor  — CSW event publishing
└── HmiServer                   — embedded WebSocket+REST server for the browser HMI
```

The three controller actors each own a single TCP socket and run independently. The
status connection is fully isolated from command traffic, so QR/AI polling never
contends with `XQ` dispatch at either the socket or actor-mailbox level.

Hardware details (motor type, limit switches, position source) are read directly from
the controller during initialization. Per-axis embedded program errors (`ae[i]`),
controller error latches (`TC`), and TCP connection drops all funnel through the
`InternalStateActor.EnterFaulted` path, which transitions the HCD to `Faulted` and
the affected axes to appropriate recovery states. The `faultReset` command then
re-establishes connections (when needed) and clears the latched fault.

## Subprojects

| Module | Description |
|--------|-------------|
| **galil-hcd** | HCD implementation — actor architecture, command handling, state management, embedded program verification, fault recovery, embedded HMI |
| **galil-io** | Low-level Galil communication library (TCP/UDP, binary QR DataRecord parsing) |
| **galil-assembly** | Assembly that talks to the Galil HCD |
| **galil-client** | Client applications for the Galil assembly or HCD |
| **galil-simulator** | Galil device simulator (motion emulation, thread management, QR DataRecords, `_XQ`/`ae[]` lifecycle) |
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

The HCD is launched with two configuration files:

- The CSW container conf (`GalilHcd.conf` or `GalilHcdSim.conf`) is passed via
  `--local`. It carries the CSW prefix and component identity.
- The HCD application conf (`GalilHcdConfig-*.conf`) is selected via the
  `-Dgalil.config.path=` system property and provides controller connection and
  axis configuration.

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

A hardware instance registers as `aps.ICS.HCD.GalilMotion.<id>` and serves the
HMI on port `9090 + controller.id`. The example below uses the lab controller
config (id = 1, HMI on 9091); the STB config (`GalilHcdConfig-STB.conf`) is
analogous.

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

See the [galil-hcd README](galil-hcd/) for the full set of configuration files,
test instructions, command documentation, fault recovery details, and HMI features.

## References

- [CSW Documentation](https://tmtsoftware.github.io/csw/6.0.0/)
- [CSW Source](https://github.com/tmtsoftware/csw)
- [CSW Example Components](https://github.com/tmtsoftware/csw-vslice-example)
- [Galil DMC Programming Reference](https://www.galil.com/downloads/manuals-and-data-sheets)
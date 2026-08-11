# APS-ICS Galil Prototype

A working prototype for the TMT Alignment and Phasing System (APS) Instrument Control
Software (ICS), built on the TMT Common Software ([CSW](https://github.com/tmtsoftware/csw))
framework. It implements three layers end to end:

- **GalilMotion HCD** (`galil-hcd`) — a thin CSW Hardware Control Daemon that drives
  Galil DMC-500x0 motion controllers and publishes their state.
- **ICS Assemblies** (`ics-assemblies`) — the full set of APS motion assemblies (APT,
  FO&C, PIT, PSH, STIM mechanisms, including the tracking K-Mirror) on a shared base,
  each exposing a high-level CSW ICD command interface on top of the HCD.
- **Engineering UI** (`aps-ics-ui`) — a React/esw-ts web UI for commanding and
  monitoring every assembly.

```
   Sequencer / CSW client / Engineering UI (aps-ics-ui)
            │  CSW ICD commands + events
            ▼
   ICS Assemblies (ics-assemblies)   — 16 assemblies on a shared MotionAssemblyHandlers base
            │  HCD command set + CurrentState
            ▼
   GalilMotion HCD (galil-hcd)       — one HCD per Galil controller
            │  Galil command set (TCP/UDP)
            ▼
   Galil DMC-500x0 controllers (or simulator)
```

The HCD is a **thin orchestrator** — motion algorithms live in embedded DMC programs
on the Galil controller, not in the HCD.

## HCD Architecture

The HCD's responsibilities are:

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
| **ics-assemblies** | The APS ICS motion assemblies (APT, FO&C, PIT, PSH, STIM; 16 in all, including the tracking K-Mirror) on a shared `MotionAssemblyHandlers` base, plus a TCS PupilRotation simulator |
| **aps-ics-ui** | React / esw-ts engineering UI for commanding and monitoring every assembly |
| **galil-io** | Low-level Galil communication library (TCP/UDP, binary QR DataRecord parsing) |
| **galil-assembly** | Early single-assembly example that talks to the Galil HCD (superseded by `ics-assemblies`) |
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

- The CSW container conf (`GalilHcd1.conf` or `GalilHcdSim.conf`) is passed via
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

A hardware instance serves the HMI on port `9090 + controller.id`. The example
below uses the lab controller config (id = 1, HMI on 9091); the STB config
(`GalilHcdConfig-STB.conf`) is analogous. The CSW prefix (for example
`aps.ICS.HCD.GalilMotion.1`) is set by the container conf and is independent of
`controller.id`; the two need not share a value.

```bash
# Terminal 1: Start CSW services
csw-services start

# Terminal 2: Build and launch the HCD against hardware
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd1.conf \
  -Dgalil.config.path=GalilHcdConfig-Hardware.conf

# Open browser to http://localhost:9091
```

See the [galil-hcd README](galil-hcd/) for the full set of configuration files,
test instructions, command documentation, fault recovery details, and HMI features.

## ICS Assemblies

The 16 APS motion assemblies live in `ics-assemblies`, each exposing a high-level CSW
ICD on top of the HCD. They share a common `MotionAssemblyHandlers` base (lifecycle,
HCD location tracking, the operational/command state machine, error recovery, and
telemetry) with three specializations: linear/single-axis **stages**, indexed
**filter wheels**, and **pupil-mask wheels**; the continuous-rotation **K-Mirror**
extends the base directly and adds TCS-driven slewing and tracking.

They run together in one container (`IcsContainerApp` / `IcsAssembliesContainer.conf`)
or individually for bring-up. A standalone TCS PupilRotation simulator exercises the
K-Mirror without a real TCS. See the [ics-assemblies README](ics-assemblies/) for the
assembly inventory, controller bindings, state model, error handling, the K-Mirror
tracking design, and run instructions.

## Engineering UI

`aps-ics-ui` is a React / [esw-ts](https://github.com/tmtsoftware/esw-ts) web UI for
commanding and monitoring every assembly: a descriptor-driven registry maps each
assembly to its command/status panels, with live telemetry, a config view, and a
command/event log. See the [aps-ics-ui README](aps-ics-ui/).

## References

- [CSW Documentation](https://tmtsoftware.github.io/csw/6.0.0/)
- [CSW Source](https://github.com/tmtsoftware/csw)
- [CSW Example Components](https://github.com/tmtsoftware/csw-vslice-example)
- [Galil DMC Programming Reference](https://www.galil.com/downloads/manuals-and-data-sheets)
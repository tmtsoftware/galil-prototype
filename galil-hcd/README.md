# GalilMotion HCD

The Galil HCD implements the CSW Hardware Control Daemon interface for Galil DMC-500x0
motion controllers. It manages embedded program execution, state monitoring, and
CSW event publishing for one or more axes.

See the [CSW documentation](https://tmtsoftware.github.io/csw/6.0.0/) for how HCDs
are defined and used in the TMT software architecture.

## Configuration

The HCD uses two layers of configuration:

1. **CSW container config** (`GalilHcd.conf`) — Component registration and program file paths.
   This is the file passed to `ContainerCmd` with `--local`.

2. **HCD application config** (`GalilHcdConfig*.conf`) — Controller connection, axis setup,
   polling rates, and simulation mode. Selected via system property.

### Config Files (in `src/main/resources/`)

| File | Purpose |
|------|---------|
| `GalilHcd.conf` | CSW container config (component registration) |
| `GalilHcdConfig.conf` | Default HCD config (currently simulator mode) |
| `GalilHcdConfig-Simulator.conf` | Simulator at 127.0.0.1:8888 |
| `GalilHcdConfig-Hardware.conf` | Hardware controller at 192.168.86.41:23 |

### Selecting a Configuration

Set the system property `-Dgalil.config.path` to choose which HCD config to load:

```bash
# Use hardware config
-Dgalil.config.path=GalilHcdConfig-Hardware.conf

# Use simulator config (or just use the default)
-Dgalil.config.path=GalilHcdConfig-Simulator.conf
```

If the property is not set, the HCD loads `GalilHcdConfig.conf` (the default).

### Controller Config Structure

```hocon
controller {
  host = [192, 168, 86, 41]     # IP as integer array
  port = 23                      # TCP port
  id = 1                         # Controller instance ID
  embeddedProgram = "protoHCD_lab.dmc"  # DMC program file
  standbyPollingRateHz = 1.0     # QR rate when all axes idle
  actionPollingRateHz = 10.0     # QR rate when any axis active
}

simulate = false                 # true for simulator mode

activeAxes = [true, true, false, false, false, false, false, false]

axes {
  A {
    mechanismType = "rotating"   # "linear" or "rotating"
    upperLimit = 360.0
    lowerLimit = 0.0
    algorithm = "shortest"       # "forward" or "shortest"
    inPositionThreshold = 1.0    # Position tolerance (counts)
    indexOffset = 0.0            # Post-home offset
  }
  # B { ... }
}
```

## CSW Commands

### Immediate Commands

These complete synchronously and return a final response directly:

- **configAxis** — Set motor parameters (speed, acceleration, deceleration, motion delay).
  Sends Galil commands directly (e.g. `SP`, `AC`, `DC`) and updates InternalState.

### Long-Running Commands

These return `Started` immediately and complete asynchronously via CommandResponseManager:

| Command | Description | Completion |
|---------|-------------|------------|
| **positionAxis** | Move axis to absolute position | Idle when at target, not moving, thread released |
| **offsetAxis** | Move axis by relative distance | Same as positionAxis |
| **homeAxis** | Home axis to reference position | Idle when not moving, thread released |
| **stopAxis** | Halt active motion on axis | Idle when not moving, thread released |
| **trackAxis** | Jog-mode tracking with position/velocity targets | Tracking when thread released (motor continues) |
| **selectWheel** | Position by discrete selection | Idle when not moving, thread released |

Each long-running command spawns a CommandWatcherActor that monitors InternalState
notifications against a command-specific CompletionMask.

Move commands (`positionAxis`, `offsetAxis`) compute physics-based timeouts from
the axis motor configuration (trapezoidal velocity profile) rather than using a
fixed default.

## Embedded Programs

The Galil controller runs embedded DMC programs that implement motion algorithms.
During initialization, the HCD downloads the controller's current program, compares
it against the expected version using LCS-based diff, and logs any differences.

Program source: `src/main/resources/programs/protoHCD_lab.dmc`

| Label | Purpose |
|-------|---------|
| `#Init` | Controller initialization (motor off, clear errors) |
| `#SetupA`–`#SetupH` | Per-axis setup (motor type, limits, speed config) |
| `#MoveA`–`#MoveH` | Absolute position move (PA mode) |
| `#HomeA`–`#HomeH` | Home sequence (find index, set origin) |
| `#StopA`–`#StopH` | Controlled stop (ST command + cleanup) |
| `#TrackA`–`#TrackH` | Jog-mode tracking (JG mode with target updates) |
| `#SelectA`–`#SelectH` | Discrete wheel/filter position selection |

Thread 0 is reserved for general-purpose operations (#Init). Threads 1–7 are
allocated dynamically by the HCD from the hardware thread pool (`MG _NO`
bitmask) for all axis operations including Setup.

## HMI Test Console

The HCD includes an embedded browser-based HMI for real-time motor control and
monitoring. It is served directly from the HCD process — no separate web server
or build step required.

### Starting the HMI

The HMI starts automatically when the HCD initializes. Open a browser to the
appropriate port:

| Mode | HMI URL | Controller |
|------|---------|------------|
| Hardware (id=1) | `http://localhost:9091` | DMC-500x0 at 192.168.86.41:23 |
| Simulator | `http://localhost:9090` | GalilSimulatorApp at 127.0.0.1:8888 |

#### With Real Hardware

```bash
# Terminal 1: Start CSW Location Service (required)
csw-services start

# Terminal 2: Build and launch the HCD
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd.conf \
  -Dgalil.config.path=GalilHcdConfig-Hardware.conf

# Open browser to http://localhost:9091  (9090 + controller id=1)
```

#### With Simulator

The simulator must use `GalilHcdSim.conf` (not `GalilHcd.conf`) so that it
registers under the distinct prefix `aps.ICS.HCD.GalilMotion.Sim`. Using
`GalilHcd.conf` would register the same prefix as a hardware instance,
causing Location Service conflicts.

```bash
# Terminal 1: Start CSW Location Service (required)
csw-services start

# Terminal 2: Start the Galil simulator
sbt "galil-simulator/run"

# Terminal 3: Build and launch the HCD
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcdSim.conf \
  -Dgalil.config.path=GalilHcdConfig-Simulator.conf

# Open browser to http://localhost:9090
```

### HMI Features

- **Real-time axis cards** — Position, velocity, demand, boolean flags (InPos,
  Stepper, Motor, FwdLim, RevLim), command state, and error display per axis.
  Active axes are fully interactive; inactive axes show dimmed telemetry with
  an override toggle.
- **Command controls** — Home, Stop, Position, Offset, Wheel, and Track buttons
  per axis. Position/offset targets entered as encoder counts.
- **Motor configuration** — Collapsible panel per axis for Speed, Acceleration,
  Deceleration, Index Offset, Index Speed, and Threshold. Values auto-populate
  from controller; Reload refreshes, Apply sends configAxis.
- **Position chart** — Real-time line chart of axis positions (Recharts, active
  axes only, ~2Hz sampling).
- **Controller console** — Galil MG output from embedded programs, displayed in
  hardware mode (MG routing not available in simulator). Color-coded: errors red,
  homed green, moves blue. Arrives via the unified log stream — no separate TCP handle.
- **Log panel** — Unified CSW log stream from all HCD actors. Runtime log level
  control via dropdown (INFO/DEBUG/TRACE). Framework actors (e.g. `pub-sub-component`)
  filtered from display by default to reduce noise.
- **Simulation indicator** — Amber "SIMULATING" badge and top border when running
  against the simulator.
- **WebSocket streaming** — State updates at QR polling rate (1Hz standby, 10Hz
  active). Automatic reconnection on disconnect.

### Architecture

The HMI runs entirely within the HCD process:

- **HmiServer** (Pekko HTTP) — WebSocket `/ws/state` for streaming, REST
  `POST /api/command` for commands, `GET /api/loglevel` / `POST /api/loglevel`
  for runtime log level control.
- **HmiJsonProtocol** — Serializes HcdState/AxisState/AxisCmdState to JSON
  using play-json.
- **HmiLogAppender** — CSW log appender that routes all HCD log output to the
  HMI WebSocket stream. Galil MG output from embedded programs arrives here
  automatically — it is emitted via `log.info` by `ControllerInterfaceActor`
  and labelled "Console" in the display. Per-actor exclusion list suppresses
  high-frequency CSW framework actors (e.g. `pub-sub-component`) from the HMI
  panel without affecting file log output.
- **index.html** — Single-file React SPA (CDN, no build step). Vanilla
  `React.createElement` calls — no JSX transpilation needed.

## Running Tests

### Unit Tests (no hardware or simulator required)

These tests use mock actors and run without any external dependencies:

```bash
# All standalone unit tests
sbt "galil-hcd/testOnly *ConfigTest *InternalStateActorTest *StatusMonitorTest *CommandHandlerActorTest *CommandWatcherActorTest *LongRunningCommandTest *AxisStateValidationTest"
```

Individual test suites:

```bash
sbt "galil-hcd/testOnly *GalilHcdConfigTest"          # 9 tests — config parsing
sbt "galil-hcd/testOnly *InternalStateActorTest"       # 41 tests — state management
sbt "galil-hcd/testOnly *StatusMonitorTest"            # 19 tests — QR polling, adaptive rate
sbt "galil-hcd/testOnly *CommandHandlerActorTest"      # 17 tests — immediate commands
sbt "galil-hcd/testOnly *CommandWatcherActorTest"      # 15 tests — completion mask evaluation
sbt "galil-hcd/testOnly *LongRunningCommandTest"       # 29 tests — motion command handlers
sbt "galil-hcd/testOnly *AxisStateValidationTest"      # 14 tests — state machine rules, interruption mechanics
```

### Controller/Simulator-Dependent Tests (no CSW services)

These tests connect directly to a Galil device or simulator. No CSW services
are required or desired — stop `csw-services` before running.

`ControllerInterfaceActorTest` can run against either hardware or simulator:

```bash
# Against simulator (default — starts simulator first):
sbt "galil-simulator/run"   # Terminal 1
sbt "galil-hcd/testOnly *ControllerInterfaceActorTest"     # 16 tests

# Against hardware (uses GALIL_HOST/GALIL_PORT env vars or galil.host/galil.port props):
sbt "galil-hcd/testOnly *ControllerInterfaceActorTest"     # 16 tests
```

`CurrentStatePublisherActorTest` requires the simulator only:

```bash
# Terminal 1: Start the simulator
sbt "galil-simulator/run"

# Terminal 2: Run test (no CSW services)
sbt "galil-hcd/testOnly *CurrentStatePublisherActorTest"   # 4 tests
```

### HCD Integration Tests

The integration tests exercise the full stack — CSW framework, actor architecture,
Galil communication (real or simulated), and embedded program execution. Coverage
includes homing, positioning, stopping, command interruption (stop-interrupts-move,
move-interrupts-move), concurrent multi-axis motion, zero-distance moves,
configuration commands, and tracking.

All 15 tests pass against both real hardware and the simulator.

`HcdIntegrationTest` uses `FrameworkTestKit` which starts its own embedded CSW
cluster. **CSW services must not be running** when executing this test.

```bash
# Against real hardware (requires Galil DMC-500x0 at 192.168.86.41:23):
sbt "galil-hcd/testOnly *HcdIntegrationTest"              # 15 tests, ~35s

# Against simulator (requires GalilSimulatorApp running on 127.0.0.1:8888):
sbt "galil-simulator/run"   # Terminal 1
sbt -Dgalil.config.path=GalilHcdConfig-Simulator.conf "galil-hcd/testOnly *HcdIntegrationTest"  # 15 tests, ~30s
```

**Note:** Do not run hardware and simulator integration tests concurrently — both
use FrameworkTestKit which binds the same Pekko Remoting TCP port.

### Test Summary

| Suite | Tests | Dependencies |
|-------|------:|-------------|
| GalilHcdConfigTest | 9 | None |
| InternalStateActorTest | 41 | None |
| StatusMonitorTest | 19 | None |
| CommandHandlerActorTest | 17 | None |
| CommandWatcherActorTest | 15 | None |
| LongRunningCommandTest | 29 | None |
| AxisStateValidationTest | 14 | None |
| ControllerInterfaceActorTest | 16 | Hardware or Simulator (no CSW services) |
| CurrentStatePublisherActorTest | 4 | Simulator (no CSW services) |
| **HcdIntegrationTest** | **15** | **Hardware or Simulator (no CSW services)** |
| **Total** | **164 standalone + 15 integration** | |
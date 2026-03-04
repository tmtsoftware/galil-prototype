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
| Hardware | `http://localhost:9090` | DMC-500x0 at 192.168.86.41:23 |
| Simulator | `http://localhost:9091` | GalilSimulatorApp at 127.0.0.1:8888 |

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

# Open browser to http://localhost:9090
```

#### With Simulator

```bash
# Terminal 1: Start CSW Location Service (required)
csw-services start

# Terminal 2: Start the Galil simulator
sbt "galil-simulator/run"

# Terminal 3: Build and launch the HCD
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd.conf \
  -Dgalil.config.path=GalilHcdConfig-Simulator.conf

# Open browser to http://localhost:9091
```

**Note:** Hardware and simulator HCDs cannot run simultaneously — both register
the same component name (`APS.ICS.HCD.GalilMotion`) with the CSW Location
Service. Stop one before starting the other.

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
- **Controller console** — MG output from embedded programs (hardware mode only;
  skipped in simulation mode). Color-coded: errors red, homed green, moves blue.
- **Simulation indicator** — Amber "SIMULATING" badge and top border when running
  against the simulator.
- **WebSocket streaming** — State updates at QR polling rate (1Hz standby, 10Hz
  active). Automatic reconnection on disconnect.

### Architecture

The HMI runs entirely within the HCD process:

- **HmiServer** (Pekko HTTP) — WebSocket `/ws/state` for streaming, REST
  `POST /api/command` for commands, `GET /api/console` for buffered console
  history.
- **HmiJsonProtocol** — Serializes HcdState/AxisState/AxisCmdState to JSON
  using play-json.
- **ConsoleMessageReader** — Separate TCP handle to controller for MG output
  (hardware mode only). Uses `CF I` + `CW 2` for ASCII routing.
- **index.html** — Single-file React SPA (CDN, no build step). Vanilla
  `React.createElement` calls — no JSX transpilation needed.

## Running Tests

### Unit Tests (no hardware or simulator required)

These tests use mock actors and run without any external dependencies:

```bash
# All standalone unit tests
sbt "galil-hcd/testOnly *ConfigTest *InternalStateActorTest *StatusMonitorTest *CommandHandlerActorTest *CommandWatcherActorTest *LongRunningCommandTest"
```

Individual test suites:

```bash
sbt "galil-hcd/testOnly *GalilHcdConfigTest"          # 9 tests — config parsing
sbt "galil-hcd/testOnly *InternalStateActorTest"       # 30 tests — state management
sbt "galil-hcd/testOnly *StatusMonitorTest"            # 19 tests — QR polling, adaptive rate
sbt "galil-hcd/testOnly *CommandHandlerActorTest"      # 16 tests — immediate commands
sbt "galil-hcd/testOnly *CommandWatcherActorTest"      # 16 tests — completion mask evaluation
sbt "galil-hcd/testOnly *LongRunningCommandTest"       # 29 tests — motion command handlers
```

### Simulator-Dependent Tests

These require the Galil simulator running on `127.0.0.1:8888`:

```bash
# Terminal 1: Start the simulator
sbt "galil-simulator/run"

# Terminal 2: Run tests (CLUSTER_SEEDS must be unset)
unset CLUSTER_SEEDS
sbt "galil-hcd/testOnly *ControllerInterfaceActorTest"     # 16 tests
sbt "galil-hcd/testOnly *CurrentStatePublisherActorTest"   # 4 tests
```

**Note:** `CLUSTER_SEEDS` must be unset for tests that use FrameworkTestKit.
The environment variable conflicts with the test kit's internal cluster formation.

### HCD Integration Tests

The integration tests exercise the full stack — CSW framework, actor architecture,
Galil communication (real or simulated), and embedded program execution. Coverage
includes homing, positioning, stopping, concurrent multi-axis motion, zero-distance
moves, configuration commands, and tracking.

All 13 tests pass against both real hardware and the simulator.

```bash
# Against real hardware (default config, requires Galil DMC-500x0 at 192.168.86.41:23):
unset CLUSTER_SEEDS
sbt "galil-hcd/testOnly *HcdIntegrationTest"              # 13 tests, ~26s

# Against simulator (requires GalilSimulatorApp running on 127.0.0.1:8888):
unset CLUSTER_SEEDS
sbt -Dgalil.config.path=GalilHcdConfig-Simulator.conf "galil-hcd/testOnly *HcdIntegrationTest"  # 13 tests, ~21s
```

**Note:** Do not run hardware and simulator integration tests concurrently — both
use FrameworkTestKit which binds the same Pekko Remoting TCP port.

### Test Summary

| Suite | Tests | Dependencies |
|-------|------:|-------------|
| GalilHcdConfigTest | 9 | None |
| InternalStateActorTest | 30 | None |
| StatusMonitorTest | 19 | None |
| CommandHandlerActorTest | 16 | None |
| CommandWatcherActorTest | 16 | None |
| LongRunningCommandTest | 29 | None |
| ControllerInterfaceActorTest | 16 | Simulator |
| CurrentStatePublisherActorTest | 4 | Simulator |
| **HcdIntegrationTest** | **13** | **Hardware or Simulator** |
| **Total** | **152 + 13 integration** | |
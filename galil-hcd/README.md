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
| **selectWheel** | Position by discrete selection | Idle when not moving, thread released (pending) |

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

| Label | Thread | Purpose |
|-------|--------|---------|
| `#Init` | 0 | Controller initialization (motor off, clear errors) |
| `#SetupA`–`#SetupH` | 1–7 | Per-axis setup (motor type, limits, speed config) |
| `#MoveA`–`#MoveH` | pool | Absolute position move (PA mode) |
| `#HomeA`–`#HomeH` | pool | Home sequence (find index, set origin) |
| `#StopA`–`#StopH` | pool | Controlled stop (ST command + cleanup) |
| `#TrackA`–`#TrackH` | pool | Jog-mode tracking (JG mode with target updates) |

Thread 0 is reserved for general-purpose operations. Threads 1–7 are allocated
dynamically from the hardware thread pool

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
sbt "galil-hcd/testOnly *CommandWatcherActorTest"      # 15 tests — completion mask evaluation
sbt "galil-hcd/testOnly *LongRunningCommandTest"       # 24 tests — motion command handlers
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

### Hardware Integration Tests

These require a physical Galil DMC-4143 controller at `192.168.86.41:23` with
stepper motors on axes A and B:

```bash
unset CLUSTER_SEEDS
sbt "galil-hcd/testOnly *HardwareIntegrationTest"      # 13 tests, ~23s
```

The integration tests exercise the full stack — CSW framework, actor architecture,
real Galil communication, and embedded program execution. Coverage includes homing,
positioning, stopping, concurrent multi-axis motion, zero-distance moves,
configuration commands, and tracking.

### Test Summary

| Suite | Tests | Dependencies |
|-------|------:|-------------|
| GalilHcdConfigTest | 9 | None |
| InternalStateActorTest | 30 | None |
| StatusMonitorTest | 19 | None |
| CommandHandlerActorTest | 16 | None |
| CommandWatcherActorTest | 15 | None |
| LongRunningCommandTest | 24 + 5 ignored | None |
| ControllerInterfaceActorTest | 16 | Simulator |
| CurrentStatePublisherActorTest | 4 | Simulator |
| **HardwareIntegrationTest** | **13** | **Hardware** |
| **Total** | **146 + 5 ignored** | |

The 5 ignored tests are for `selectWheel` — the embedded `#SelectX` programs
have not yet been created on the test bench controller.

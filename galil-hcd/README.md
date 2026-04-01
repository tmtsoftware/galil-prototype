# GalilMotion HCD

The Galil HCD implements the CSW Hardware Control Daemon interface for Galil DMC-500x0
motion controllers. It manages embedded program execution, state monitoring, and
CSW event publishing for axes configured as either linear or rotating mechanisms.

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
| `GalilHcd.conf` | CSW container config (hardware component registration) |
| `GalilHcdSim.conf` | CSW container config (simulator — different prefix to avoid Location Service conflicts) |
| `GalilHcdConfig.conf` | Default HCD config (simulator mode) |
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

If the property is not set, the HCD loads `GalilHcdConfig.conf` (the default, simulator mode).

### Controller Config Structure

```hocon
controller {
  host = [192, 168, 86, 41]     # IP as integer array
  port = 23                      # TCP port
  id = 1                         # Controller instance ID (affects HMI port: 9090 + id)
  embeddedProgram = "protoHCD_lab.dmc"
  standbyPollingRateHz = 1.0     # QR rate when all axes idle
  actionPollingRateHz = 10.0     # QR rate when any axis active
}

simulate = false                 # true for simulator mode

# 8 booleans for axes A-H; reflects physical wiring, not controller model
activeAxes = [true, true, false, false, false, false, false, false]

axes {
  A {
    mechanismType = "linear"     # "linear" or "rotating"
    upperLimit = 1000.0          # soft limit (counts)
    lowerLimit = 0.0
    inPositionThreshold = 5.0    # position tolerance (counts)
    indexOffset = 0.0
  }
  B {
    mechanismType = "rotating"
    algorithm = "shortest"       # "forward" | "reverse" | "shortest"
    countsPerRevolution = 400    # integer counts for one full 360 degree revolution
    upperLimit = 360.0
    lowerLimit = 0.0
    inPositionThreshold = 1.0
    indexOffset = 0.0
    # Optional -- overwrite controller EEPROM defaults:
    maxSpeed = 1000.0            # counts/sec
    acceleration = 9216.0        # counts/sec^2
    deceleration = 9216.0
    motionDelay = 100.0          # post-move settling time (ms)
    indexSpeed = 256.0           # homing speed (counts/sec)
  }
}
```

### `countsPerRevolution` for Rotating Axes

For rotating axes, `countsPerRevolution` is the integer number of stepper counts for
one full 360 degree revolution. This must always be a whole number for stepper motors.
Example: a 400-step/rev stepper uses `countsPerRevolution = 400`.

**Both hardware and simulator configs use `cpr=400`** — the simulator is configured to
match the hardware axis types so that integration tests behave identically on both.

The HCD writes this value to the `cpr[]` embedded variable array at initialization
via `writeMotionConfig()`. The embedded `#SelectX` programs use cpr[idx] for
exact integer slot arithmetic.

---

## CSW Commands

### Immediate Commands

- **configAxis** — Set motion parameters (speed, acceleration, deceleration, motion delay,
  index offset, index speed, inPosition threshold).
- **configLinearAxis** — Configure axis as linear with soft limits.
- **configRotatingAxis** — Configure axis as rotating with approach algorithm.
- **setBit** — Set or clear a digital output bit. Parameters: `address` (int, 1-based per
  Galil convention), `value` (int: 1=set, 0=clear). Sends `SB n` or `CB n` to controller.

### Long-Running Commands

| Command | Description | Completion condition |
|---------|-------------|---------------------|
| **positionAxis** | Move to absolute count position | Idle, inPosition, thread released |
| **offsetAxis** | Move by relative distance | Idle, inPosition, thread released |
| **homeAxis** | Home axis to reference | Idle, not moving, thread released |
| **stopAxis** | Halt active motion | Idle, not moving, thread released |
| **trackAxis** | Jog-mode tracking | Tracking, thread released (motor continues) |
| **selectWheel** | Position to discrete slot (0-7) | Idle, not moving, thread released |
| **positionWheel** | Position to angular demand (degrees) | Idle, inPosition, thread released |

Move commands compute physics-based timeouts from the axis motor configuration
(trapezoidal velocity profile with 2x safety factor, 3-second minimum).

### Rotating Axis Approach Algorithm

For rotating axes with `countsPerRevolution` configured, `positionAxis`, `offsetAxis`,
and `positionWheel` apply an approach algorithm that resolves the correct absolute count target:

- **forward** — always approach from below (add one revolution if needed)
- **reverse** — always approach from above (subtract one revolution if needed)
- **shortest** — take whichever arc is shorter

`positionWheel` converts the angular demand in degrees to counts via
`rawTarget = (angleDeg / 360.0) * countsPerRevolution`, then applies the approach algorithm
identically to `positionAxis`. The command is rejected if the axis is not configured as
Rotating or if `countsPerRevolution` is not set.

`selectWheel` does not use the approach algorithm — it delegates to the embedded `#SelectX` program.

---

## Embedded Programs

Program source: `src/main/resources/programs/protoHCD_lab.dmc`

| Label | Purpose |
|-------|---------|
| `#Init` | Controller initialization (motor off, create arrays, set defaults) |
| `#SetupA`-`#SetupH` | Per-axis hardware config (motor type, limits, amplifier) |
| `#MoveA`-`#MoveH` | Absolute position move (PA mode) |
| `#HomeA`-`#HomeH` | Home sequence |
| `#StopA`-`#StopH` | Controlled stop |
| `#TrackA`-`#TrackH` | Jog-mode tracking with position/velocity targets |
| `#SelectA`-`#SelectH` | Discrete 8-position wheel: `PA = dmd[idx] * (cpr[idx] / 8)` |

**Key embedded arrays:**
- `cpr[8]` — counts per revolution (integer; written by HCD `writeMotionConfig()`)
- `dmd[8]` — demand/target (counts for move; slot number 0-7 for select)
- `speed[]`, `accel[]`, `decel[]`, `hspd[]`, `hoff[]`, `mdelay[]` — motion parameters

Thread 0 is reserved for `#Init`. Threads 1-7 are allocated dynamically from
the `MG _NO` bitmask for all axis operations.

---

## HMI Test Console

The HCD includes an embedded browser-based HMI. No separate web server needed.

### Starting the HMI

| Mode | HMI URL | Controller |
|------|---------|------------|
| Hardware (id=1) | `http://localhost:9091` | DMC-500x0 at 192.168.86.41:23 |
| Simulator | `http://localhost:9090` | GalilSimulatorApp at 127.0.0.1:8888 |

#### With Real Hardware

```bash
# Terminal 1: Start CSW Location Service
csw-services start

# Terminal 2: Build and launch
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd.conf \
  -Dgalil.config.path=GalilHcdConfig-Hardware.conf
```

#### With Simulator

Use `GalilHcdSim.conf` to register under a distinct prefix and avoid Location Service
conflicts with hardware instances.

```bash
# Terminal 1: Start CSW Location Service
csw-services start

# Terminal 2: Start the Galil simulator
sbt "galil-simulator/run"

# Terminal 3: Build and launch HCD
sbt stage
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcdSim.conf \
  -Dgalil.config.path=GalilHcdConfig-Simulator.conf
```

### HMI Features

**Axis cards** — One card per axis reported by the controller (reflects physical hardware
axis count, not `activeAxes` config).

- **Active axes:** full card with telemetry, type-specific visual, command controls
- **Inactive axes:** collapsed to slim header strip; click `INACTIVE` to expand (override mode)

**Type-specific visuals:**
- **Rotating:** dial with needle, cardinal ticks (0/90/180/270), angle readout. Demand line
  shown only during/after position or offset commands.
- **Linear:** vertical track with position arrow from left, limit labels at ends. Arrow turns
  green when `inPosition`.

**Position display:** For rotating axes, `Position` shows the wrapped value in `[0, cpr)` —
matching the demand space used by commands — with a smaller `Raw` readout below it showing
the accumulated encoder count for diagnostics. For linear axes both values are identical.

**Command controls per axis:** Home, Stop, Position (counts), Offset (counts), Angular
(degrees — `positionWheel`, rotating axes), Wheel (slot 0-7 — `selectWheel`, rotating axes),
Track (position + velocity). Collapsible Config panel for motion parameters, mechanism type
(Rotating: approach algorithm; Linear: soft limits).

**Other panels:** real-time position chart, collapsible I/O panel (full-width, between chart
and log), unified log panel with runtime level control (INFO/DEBUG/TRACE), thread status bar,
SIMULATING badge in simulator mode.

**I/O panel:** Shows 16 digital inputs (read-only) and 16 digital outputs (clickable toggle →
`setBit`). The number of active channels is an intrinsic property of the controller model:
DMC-50040 (4-axis) provides 8 DI / 8 DO (bits 1-8 live, 9-16 dimmed); DMC-50080 (8-axis)
provides 16 DI / 16 DO (all bits live). No expansion module is involved. Channel availability
is determined by `controllerAxisCount` from the controller `ID` command, not by the number of
configured axes. Also shows 8 analog input channels (polled at 1Hz via `MG @AN[n]`, displayed
in volts). Collapsed by default; header shows mini 8-dot DI and DO summary indicators.

**Log panel:** Collapsible — click the `HCD LOG` label or chevron to minimize to a header
bar, freeing vertical space. Line count badge visible when minimized. Log level controls
(HCD runtime level and display filter) remain accessible in both states.

**Connection status:** Header shows three dot indicators — `Cmd` (command TCP), `Sts` (status
TCP), `Con` (console TCP, hardware-only, informational). Green = Connected, red = Disconnected
(gray for console since it is not required for operation). `isOperational` requires both Cmd
and Sts to be Connected.

### Architecture

The HCD uses three independent TCP connections to the controller (all on the same port —
the DMC-500x0 assigns each to one of its 8 Ethernet handles internally). All three actors
are spawned as siblings directly under `GalilHcd` — none is a child of another:

| Actor | Connection | Role |
|-------|-----------|------|
| `ControllerCommandActor` | command socket | SendCommand, ExecuteProgram, HaltExecution, thread allocation |
| `ControllerStatusActor` | status socket | QR polling, analog input queries |
| `ControllerConsoleActor` | console socket | Unsolicited MG output via `CF I` (hardware only) |

Each actor reports its connection status to `InternalStateActor` on startup via
`ReportConnectionStatus`. `HcdState.isOperational` is true when both command and status
connections are `Connected` (console is informational and does not affect readiness).

This means QR/AI polls never contend with command traffic at either the socket or actor-mailbox level.

- **HmiServer** (Pekko HTTP) — WebSocket `/ws/state`, REST `POST /api/command`, `GET/POST /api/loglevel`
- **HmiJsonProtocol** — Serializes HcdState to JSON
- **HmiLogAppender** — Routes all CSW log output (including Galil MG console) to WebSocket
- **index.html** — Single-file React SPA, vanilla `createElement`, no build step

---

## Running Tests

### Unit Tests (no hardware or CSW services required)

```bash
sbt "galil-hcd/testOnly *ConfigTest *InternalStateActorTest *ControllerStatusActorTest \
  *CommandHandlerActorTest *CommandWatcherActorTest *LongRunningCommandTest \
  *RotatingMechanismTest *AxisStateValidationTest *IOTest"
```

| Suite | Tests | Coverage |
|-------|------:|---------|
| GalilHcdConfigTest | 9 | Config parsing, countsPerRevolution |
| InternalStateActorTest | 54 | State management, pub/sub, motorPosition/motorDemand/angularPosition, ConnectionStatus |
| ControllerStatusActorTest | 15 | QR polling, adaptive rate, analog input polling |
| CommandHandlerActorTest | 17 | Immediate commands, validation |
| CommandWatcherActorTest | 15 | Completion mask evaluation |
| LongRunningCommandTest | 29 | Motion command handlers |
| RotatingMechanismTest | 26 | Approach algorithm, positionWheel, offsetAxis, no-cpr fallback |
| AxisStateValidationTest | 14 | State machine rules, interruption mechanics |
| IOTest | 17 | DIO bit extraction, setBit/clearBit dispatch, analog input polling |

### Controller/Simulator-Dependent Tests (no CSW services)

```bash
sbt "galil-simulator/run"   # Terminal 1 (for simulator tests)
sbt "galil-hcd/testOnly *ControllerCommandActorTest"        # 16 tests
sbt "galil-hcd/testOnly *CurrentStatePublisherActorTest"    # 4 tests (simulator only)
```

### Integration Tests

`HcdIntegrationTest` uses `FrameworkTestKit` — **CSW services must not be running.**

```bash
# Against hardware:
sbt -Dgalil.config.path=GalilHcdConfig-Hardware.conf \
    "galil-hcd/testOnly *HcdIntegrationTest"               # 18 tests, ~50s

# Against simulator:
sbt "galil-simulator/run"
sbt -Dgalil.config.path=GalilHcdConfig-Simulator.conf \
    "galil-hcd/testOnly *HcdIntegrationTest"               # 18 tests, ~45s
```

### Simulator Tests

```bash
sbt "galil-simulator/testOnly *GalilSimulatorActorTest"    # 73 tests
```

### Full Test Summary

| Suite | Tests | Dependencies |
|-------|------:|-------------|
| GalilHcdConfigTest | 9 | None |
| InternalStateActorTest | 54 | None |
| ControllerStatusActorTest | 15 | None |
| CommandHandlerActorTest | 17 | None |
| CommandWatcherActorTest | 15 | None |
| LongRunningCommandTest | 29 | None |
| RotatingMechanismTest | 26 | None |
| AxisStateValidationTest | 14 | None |
| IOTest | 17 | None |
| ControllerCommandActorTest | 16 | Hardware or Simulator (no CSW services) |
| CurrentStatePublisherActorTest | 4 | Simulator (no CSW services) |
| HcdIntegrationTest | 18 | Hardware or Simulator + FrameworkTestKit (no csw-services) |
| GalilSimulatorActorTest | 73 | None |
| **Total** | **307** | |
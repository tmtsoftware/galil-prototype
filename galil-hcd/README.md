# GalilMotion HCD

The Galil HCD implements the CSW Hardware Control Daemon interface for Galil DMC-500x0
motion controllers. It manages embedded program execution, state monitoring, error
detection, fault recovery, and CSW event publishing for axes configured as either
linear or rotating mechanisms.

See the [CSW documentation](https://tmtsoftware.github.io/csw/6.0.0/) for how HCDs
are defined and used in the TMT software architecture.

## Configuration

The HCD uses two layers of configuration:

1. **CSW container config** (`GalilHcd.conf` / `GalilHcdSim.conf`) — Component
   registration. This is the file passed to `ContainerCmd` with `--local`. It is the
   sole authoritative source of the CSW prefix and component identity.

2. **HCD application config** (`GalilHcdConfig*.conf`) — Controller connection, axis
   setup, polling rates, and simulation mode. Selected via the `-Dgalil.config.path=`
   system property.

### Config Files (in `src/main/resources/`)

| File | Purpose |
|------|---------|
| `GalilHcd.conf` | CSW container config (hardware component registration: `aps.ICS.HCD.GalilMotion.1`) |
| `GalilHcdSim.conf` | CSW container config (simulator — distinct prefix `aps.ICS.HCD.GalilMotion.Sim` to avoid Location Service conflicts) |
| `GalilHcdConfig.conf` | Default HCD config (simulator mode) |
| `GalilHcdConfig-Simulator.conf` | Simulator at 127.0.0.1:8888 (lab axis layout) |
| `GalilHcdConfig-Hardware.conf` | Lab DMC-500x0 (DMC-50040, 4 axes) at 192.168.86.41:23 |
| `GalilHcdConfig-STB.conf` | STB DMC-4080 (8 channels, 7 active axes A-G) at 192.168.42.100:23 |
| `GalilHcdConfig-STBsim.conf` | Simulator at 127.0.0.1:8888 with the STB axis layout (7 active axes) |

### Selecting a Configuration

Set the system property `-Dgalil.config.path` to choose which HCD config to load:

```bash
# Use lab hardware (DMC-500x0)
-Dgalil.config.path=GalilHcdConfig-Hardware.conf

# Use STB hardware (DMC-4080, 7 active axes)
-Dgalil.config.path=GalilHcdConfig-STB.conf

# Use simulator with STB axis layout
-Dgalil.config.path=GalilHcdConfig-STBsim.conf

# Use simulator with lab axis layout (or just use the default)
-Dgalil.config.path=GalilHcdConfig-Simulator.conf
```

If the property is not set, the HCD loads `GalilHcdConfig.conf` (the default, simulator mode).

### Controller Config Structure

```hocon
controller {
  host = [192, 168, 86, 41]     # IP as integer array
  port = 23                      # TCP port
  id = 1                         # Controller instance ID (affects HMI port: 9090 + id for hardware)
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
- **setAO** — Set an analog output channel. Parameters: `address` (int), `value` (float).
- **faultReset** — Recover from a Faulted HCD state. Parameter: `severity` (Choice of
  `None`, `Init`, `Minor`, `Major`). See [Fault Recovery](#fault-recovery) below for behavior.

### Long-Running Commands

| Command | Description | Completion condition |
|---------|-------------|---------------------|
| **positionAxis** | Move to absolute count position | Idle, inPosition, thread released |
| **offsetAxis** | Move by relative distance | Idle, inPosition, thread released |
| **homeAxis** | Home axis to reference | Idle, not moving, thread released |
| **stopAxis** | Halt active motion | Idle (or Lost), not moving, thread released |
| **selectWheel** | Position to discrete slot (0-7) | Idle, inPosition, thread released |
| **positionWheel** | Position to angular demand (degrees) | Idle, inPosition, thread released |

### Immediate (PVT-Streaming) Command

| Command | Description | Completion |
|---------|-------------|-----------|
| **trackAxis** | Stream one PVT segment (`PV<x>=ΔP,V,T` + initial `BT<x>`) | Completed on FIFO acceptance |

`trackAxis(axis, position, rate, validTime)` is the per-segment primitive for
HCD-orchestrated tracking. Each call writes one PVT segment to the controller's
per-axis FIFO; the controller does cubic-Hermite interpolation between segments
to produce a smooth trajectory. The Assembly streams these at ~1 Hz (with FIFO
slack — see the lead-margin discussion below), and the HCD owns absolute→relative
conversion and underrun monitoring. The "tracking session" is a state in IS
(`axisState = Tracking` plus a `trackingSession` ledger), not a long-running CSW
command lifecycle. See the [Tracking](#tracking) section below.

Move commands compute physics-based timeouts from the axis motor configuration
(trapezoidal velocity profile with 2x safety factor, 3-second minimum).

When the HCD is in `Faulted` state, every command except `faultReset` is rejected
with `Invalid(OtherIssue("HCD Faulted: <msg>"))`. The check is enforced both in CSW
`validateCommand` and in `HmiServer.handleCommandRequest` (the HMI bypasses CSW
validation by going directly to `CommandHandlerActor`).

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

## Axis State Machine

Per SDD Figure 4-2, with the implementation refinements documented in
`StateModel.AxisStateEnum`:

```
              ┌────────┐  homeAxis  ┌─────────┐  success     ┌──────┐
   startup → │  Lost  │ ────────→  │ Homing  │ ────────────→│ Idle │
              └────────┘             └─────────┘              └──────┘
                  ↑                       │  fault                │
                  │                       └────→  Error           │
                  │                                  ↑            │ motionCmd
                  │                                  │ fault      ↓
                  │                              ┌──────┐      ┌────────┐
                  │           stopAxis (!homed)  │      │      │ Moving │
                  └──────────────────────────────│Error │      └────────┘
                                                 │      │           │
                              stopAxis (homed)   │      │           │ trackAxis
                                ┌────────────────│      │           ↓
                                ↓                └──────┘      ┌──────────┐
                              Idle                              │ Tracking │
                                                                └──────────┘
```

A per-axis `homed: Boolean` flag distinguishes Error → Lost from Error → Idle:

- `homed=false` initially, and re-cleared at the start of every `homeAxis` attempt
- `homed=true` set atomically with `axisState=Idle` when `homeAxis` completes successfully
- `stopAxis` from `Error` returns to `Idle` if the axis was previously homed, or to
  `Lost` if the home attempt itself failed

This closes an SDD-diagram oversight: a home failure transitions Homing → Error
(via `ControllerStatusActor.reportAxisError` when `ae[i] != 0`), and the correct
recovery state coming out of Error must depend on whether a valid home reference exists.

---

## Embedded Programs

Program sources: `src/main/resources/programs/` — `protoHCD_lab.dmc` (lab DMC-500x0)
and `galilHCD_STB.dmc` (STB DMC-4080).

| Label | Purpose |
|-------|---------|
| `#Init` | Controller initialization (motor off, create arrays, set defaults) |
| `#SetupA`-`#SetupH` | Per-axis hardware config (motor type, limits, amplifier, BZ commutation) |
| `#MoveA`-`#MoveH` | Absolute position move (PA mode) |
| `#HomeA`-`#HomeH` | Home sequence |
| `#StopA`-`#StopH` | Controlled stop |
| `#SelectA`-`#SelectH` | Discrete 8-position wheel: `PA = dmd[idx] * (cpr[idx] / 8)` |
| `#POSERR`, `#LIMSWI`, `#MCTIME`, `#CMDERR` | Controller-invoked fault handlers |

Tracking is implemented in the HCD as direct PVT segment writes to the
controller's per-axis FIFO — no embedded `#TrackX` programs (removed in S64
when the architecture pivoted from JG+IP to PVT streaming).

**Key embedded arrays:**
- `cpr[8]` — counts per revolution (integer; written by HCD `writeMotionConfig()`)
- `dmd[8]` — demand/target (counts for move; slot number 0-7 for select)
- `speed[]`, `accel[]`, `decel[]`, `hspd[]`, `hoff[]`, `mdelay[]` — motion parameters
- `ae[8]` — per-axis error code, populated by both motion programs and fault handlers
  (see [Per-Axis Error Detection](#per-axis-error-detection) below)

### Embedded `ae[]` Convention

Every motion program (`#HomeX`/`#MoveX`/`#SetupX`/`#StopX`) sets
`ae[axis]=1` on entry and clears `ae[axis]=0` only on the success path. Any abort
(`#CMDERR` killing the thread, or an error handler exiting via `RE`/`RE1`) leaves
`ae[axis]=1` (program error). The fault handlers also may set ae:

- `#POSERR` → `ae[axis]=2` (position error exceeded limit)
- `#LIMSWI` → `ae[axis]=3` (limit switch hit during motion)
- `#MCTIME` → `ae[axis]=4` (motion completion timeout)

`#StopX` programs also touch `ae[axis]=0` for consistency and to clear the error
latch on operator recovery.

### Thread Management

Thread 0 is reserved for automated subroutines (`#POSERR`, `#LIMSWI` etc). 
Threads 1-7 are allocated dynamically by the HCD for per-axis motion commands. 
Allocation reads `MG _NO` to find an unused thread;

**Per-thread state** uses `MG _XQ<n>`, **not** `MG _NO` or the QR `threadStatus`
byte. Empirical discovery (Session 53): when one thread is mid-motion and another
thread's program is killed by `#CMDERR`, both `_NO` and the QR byte continue to
report the dead thread as active for many seconds, until other unrelated controller
activity settles. Per-thread `_XQ<n>` returns the line number currently executing,
or `-1` if the thread has stopped, and is reliable. `ControllerStatusActor`
synthesizes the per-scan thread bitmask from `_XQ<n>` queries each scan, falling
back to the raw QR byte only when the per-thread query fails (parse error or
simulator without `_XQ` support).

**Halt-time notification** (Session 55): when `CommandHandlerActor.checkAndInterrupt`
deliberately halts an axis's thread via `HX` (the SDD 4.8.1 interruption protocol
for `positionAxis`/`stopAxis`/`offsetAxis`/`selectWheel`/`positionWheel` preempting
an active move or home), it sends `ControllerStatusActor.NotifyAxisHalted(axis)` as
a synchronous ask immediately after a successful `HX`. The handler removes the axis
from CS's internal `axisThreads` map and replies with `NotifyAxisHaltedAck`. This
prevents the next QR scan from observing "axis registered with thread N, thread N
just cleared, `ae[axis]==1`, errorCode==0" and firing the defensive
`unexplainedAxes` check (which would otherwise report "Embedded program ended
unexpectedly" against whatever new command CH launched on the same axis — typically
the same thread number, since CH reallocates the lowest free thread). CH's
subsequent `RegisterAxisThread` re-adds the axis under the new thread number. The
ack is a synchronization point: CH must wait for it before launching the next
program so the prune is in place before the new `RegisterAxisThread`.

### Atomic XQ + Thread Confirmation

`ControllerCommandActor.ExecuteProgram` sends `XQ #label,N;MG _XQN` as a single
compound. The `MG` runs in the same line buffer as `XQ`, before any program execution
can complete or `#CMDERR`. This eliminates a previous race where a fast-completing or
fast-failing program ended before a separate `MG _NO` query could observe it.

---

## Tracking

The HCD implements tracking as direct PVT (Position-Velocity-Time) segment
streaming to the controller's per-axis FIFO. There is no embedded `#TrackX`
program — `trackAxis` is an HCD-orchestrated operation that talks PVT to the
controller.

### Wire format

Per segment, the HCD writes `PV<axis>=ΔP,V,T` to the controller. The first
segment of a tracking session also issues `BT<axis>` to begin trajectory
execution. Subsequent segments only need the `PV<axis>=` write — the controller
is already streaming through the FIFO.

| Wire | Meaning |
|------|---------|
| `PVA=ΔP,V,T` | A-axis PVT segment. Third letter IS the axis (`PVB=` for B, `PVC=` for C, ...). There is no `PVAA=`. |
| `BTA` | Begin trajectory for axis A. Project convention is always per-axis; bare `BT` (no axis) would start all axes with loaded segments. |
| `_PVA` | A-axis FIFO free-slots count (255 = empty FIFO, 0 = full). Segments in flight = `255 - _PVA`. |
| `_BTA` | A-axis segments executed since the most recent `BTA`. Resets on each new BT; not cumulative. |

### Per-segment lifecycle

`trackAxis(axis, position, rate, validTime)` from the Assembly:

1. **Validate** envelope (axis, position, rate, validTime all present) and gate
   on `controllerSamplePeriodMicros > 0` (set at init from `MG _TM`).
2. **Convert** user units to counts: rotating axes use `value * cpr / 360`
   (integer arithmetic); linear axes are passthrough.
3. **Compute** `ΔP = positionCounts - prevEndpointCounts` and
   `T_samples = round((validTime - prevValidTime) / samplePeriod)`.
4. **Guard** against degenerate `(0,0,0)` (the controller treats this as
   end-of-trajectory) and non-monotonic `validTime`.
5. **Write** the wire string — first segment includes `;BT<x>`, subsequent
   segments are `PV<x>=` only.
6. **Update IS:** `axisState = Tracking`, `trackingSession = Some(TrackingSession(...))`,
   record `lastTargetCounts`, `lastValidTime`, `segmentsSubmitted`.
7. **Complete:** `crm.updateCommand(Completed(runId))` immediately on FIFO
   acceptance. No watcher is spawned — trackAxis is `completionType=immediate`.

The "tracking session" is a state in IS, not a long-running CSW command lifecycle.
Per the invariant `axisState == Tracking ⇔ trackingSession.isDefined`, the session
is set by the first `trackAxis` from `Idle`, updated by subsequent calls in
`Tracking`, and cleared by `stopAxis` (on success path) or by `EnterFaulted`
(atomically with Tracking → Error).

### First-segment handling

When `trackAxis` arrives in `axisState = Idle`, there is no previous segment to
base ΔP on. HCD uses the polled motor position as `prevEndpointCounts` and
`Instant.now()` as `prevValidTime`. The first segment thus carries the motor
from its current physical position to the Assembly's first commanded position
over the lead-time interval. `v_start = 0` is implicit (controller infers from
rest). The Assembly is responsible for the first target being physically
achievable.

### Stopping

`stopAxis` from `Tracking` bypasses `checkAndInterrupt` entirely (there's no
embedded thread to halt under PVT) and runs `#StopX` directly. `#StopX`'s `STx`
drains the FIFO and decelerates the motor to rest. `trackingSession` is cleared
atomically with `axisState → Idle` on success.

For graceful trajectory termination (decelerate-then-stop), the Assembly can
submit a final `trackAxis(axis, target, rate=0, validTime)` to ramp velocity to
zero before issuing `stopAxis`.

### Underrun detection

When any axis is in `Tracking`, `ControllerStatusActor` adds `_PV<x>,_BT<x>` to
its QR-companion polls and forwards the readings to IS via
`ReportPvtMonitoring(readings, observedAt)`. IS checks
`observedAt > session.lastValidTime` per tracking axis; if true and the session
is still active, the axis transitions to `Error` with
`axisError = "Tracking stream underrun"` and `trackingSession` is cleared.

Detection is preemptive — it fires before the controller's FIFO physically
empties (the controller would then silently stop the motor with no error code).
The Assembly observes the fault via `CurrentStateAxis` and can react.

### Lead margin

Tracking submissions must arrive with `validTime` sufficiently far in the
future to keep the controller's FIFO non-empty between updates. The pattern
used by `TrackInjectorApp` (the standalone lab test client in `galil-client`)
is `validTime = now + 1/cadence + leadMargin`, where `leadMargin` is slack
beyond the cadence period. At 1 Hz cadence with a 0.2 s lead margin, each
segment's `validTime` is 1.2 s in the future — leaving the FIFO with ~1
segment of slack while the next update is in flight.

Lead-margin policy will ultimately be specified in the TCS-to-Assembly ICD;
the HCD only enforces strict monotonicity of `validTime`.

---

## Error Detection & Fault State Machine

The HCD detects three classes of error and routes them through a uniform fault path.

### Per-Axis Error Detection

Per-axis embedded program errors surface via `ae[]`. Each QR scan,
`ControllerStatusActor.handleQRResponse` runs the following pipeline:

1. Parse QR → snapshot raw `threadStatus` byte and `errorCode` at one moment.
2. Read per-thread state via `MG _XQ<n>` for each registered thread (single compound
   query). Synthesize a `threadStatus` byte from the per-thread results.
3. Read `MG ae[<idx1>],ae[<idx2>],...` for configured axes (single compound read).
   **Order matters: QR before `ae` reads** — the reverse order races with successful
   program endings (the program clears `ae=0` after we read it but before QR shows
   the thread cleared, giving us a stale `ae=1` to misattribute).
4. Decide per-axis errors:
   - `ae[i]=2/3/4` → report per-axis as POSERR/LIMSWI/MCTIME (deduplicated via
     `lastReportedAxisError` so repeated values don't spam IS).
   - `errorCode != 0` (controller-level) → fetch `TC 1` (consumes the latch), look
     for axes with `ae=1` AND thread just cleared this scan. **Exactly 1 candidate** →
     attribute the controller error to that axis (`axisErrorMsg = "Embedded program
     error: <TC text>"`, `axisState=Error`). **Multiple candidates** → escalate to
     HCD-Faulted via `EnterFaulted`. **Zero candidates** → defer one scan, then
     escalate if still unresolved.
   - **Defensive (`ae=1` AND thread cleared AND errorCode==0):** treat as per-axis
     Error. This catches a program ending without clearing `ae[]` and without a
     controller error, which shouldn't happen with the current embedded design.
     Suppressed for axes where `CommandHandlerActor` deliberately halted the
     thread (the `NotifyAxisHalted` post-HX prune; see Thread Management above):
     in that case the `ae==1` is the entry-time flag from a program we stopped,
     not a fault.
5. Push HCD-level updates (position, I/O, timing).
6. Push per-axis QR-derived updates (position, velocity, switches).
7. Push `UpdateThreadStatus` to IS **last** — clears `activeThread` for completed
   threads. Order is critical: `axisErrorMsg` / `axisState=Error` must reach IS
   before `activeThread=0` so the watcher's `CmdStateChanged` notification carries
   the error and fails the command on its first evaluation.

### Controller-Level Error Detection

Two complementary paths surface controller error latches (`TC` codes):

- **Init-time** (`ControllerCommandActor.sendAndWaitForThread`): after every `XQ` +
  thread-completion wait (for `#Init`, `#SetupX`, etc.), the HCD calls `TC 1` on the
  command connection. Nonzero → set `HcdState{state=Faulted, controllerErrorMsg=...}`,
  log at ERROR, throw — propagating through `initFuture` to fail HCD startup cleanly.
- **Runtime** (`ControllerStatusActor`): per-axis attribution as described above.

`TC` reads clear the controller error latch.

### Connection Loss Detection

Each TCP-owning actor independently detects and reports its own connection failure
to `InternalStateActor`. No actor assumes the state of another's connection.

- `ControllerStatusActor` — on any `IOException` (including `SocketTimeoutException`,
  "Broken pipe"): stops polling, reports `statusConnection → Disconnected`. Then
  immediately fires a `MG 1` probe to `ControllerCommandActor` to distinguish total
  controller loss from isolated status-connection failure (logged as the diagnostic
  `"Command connection probe after status loss — ALSO FAILED: ..."` or `"... — OK"`).
- `ControllerCommandActor` — on any `IOException` in any handler: logs at ERROR,
  reports `commandConnection → Disconnected`, returns error to caller.
- `ControllerConsoleActor` — `connectionLostFlag` gates `consoleConnection →
  Disconnected` on loss vs. clean shutdown.

`GalilIoTcp.read()` throws `IOException("Connection closed by remote (host:port)")`
on `-1` from `InputStream.read()`, instead of a confusing `ArrayIndexOutOfBoundsException`.

All three sockets enable `SO_KEEPALIVE` at construction, preventing silent OS-level
expiry on long-idle connections.

### Faulted State

The `Faulted` HCD state is triggered by any of: a controller error latch, loss of
the command or status TCP connection, or an embedded program error that cannot be
cleanly attributed to a single axis. All three feed through
`InternalStateActor.EnterFaulted(reason)`, which atomically:

- Sets `HcdState.state = Faulted` and `HcdState.controllerErrorMsg = reason`
- Per-axis transitions: `Homing → Lost`, `Moving/Tracking → Error`
- Clears any active commands

When CS (rather than CC) detects a controller error, it additionally fires a
fire-and-forget `ST;MO` compound via `commandActor` to safe all motors and disable
drives, since the command connection may still be alive.

The `CommandWatcherActor` subscribes to HCD `StateChanged` notifications and fails
its in-flight command immediately when `Faulted` arrives, with the
`controllerErrorMsg` as the failure reason.

### Fault Recovery

The `faultReset` command (SDD Section 4.6.4) recovers from `Faulted`. The
`severity` parameter selects the level of intervention:

| Severity | Behavior |
|----------|----------|
| **None** | Test existing sockets; reconnect any that have dropped; clear the controller error latch; transition HCD `Faulted → Ready`. Implemented. |
| **Init** | Reconnect dropped connections and re-run `#Init` and `#SetupX`. Not yet implemented. |
| **Minor** | Reset the controller and re-initialize. Not yet implemented. |
| **Major** | Reload embedded code and re-initialize. Not yet implemented. |

**Reconnection (None severity):** `handleFaultReset` asks the command actor and the
status actor to `Reconnect`, sequentially. Each actor first verifies its existing
socket (the connection may have healed on its own — cable blip, OS recovery) by
sending a benign probe (`MG 0` for command, drained-then-`QR` for status). On verify
success, no socket close is needed. On verify failure, the actor closes the dead
socket and opens a fresh `GalilIoTcp`.

**Status post-reconnect housekeeping:** drain any stale buffered data, call `TC 1`
to read and log the controller's disconnect-time error (typically
`"123 TCP lost sync or timeout"`), reset the `controllerFaulted` suppression flag,
and restart the polling timer. `TC 1` is also called during the initial status
connect (in the constructor) to clear stale errors from a previous session.

The HMI surfaces this with a `[Clear Fault]` button in the `ErrorBanner` whenever
`hcdState === 'Faulted'`. The button issues `faultReset` with `severity=None`.

---

## HMI Test Console

The HCD includes an embedded browser-based HMI. No separate web server needed.

### Starting the HMI

| Mode | HMI URL | Controller |
|------|---------|------------|
| Hardware (id=1) | `http://localhost:9091` | DMC-500x0 at 192.168.86.41:23 |
| Simulator | `http://localhost:9090` | GalilSimulatorApp at 127.0.0.1:8888 |

Hardware HMI port is `9090 + controller.id`; simulator HMI port is fixed at 9090.

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

**Error banner:** Visible whenever `hcdState === 'Faulted'`. Shows the controller error
message and a `[Clear Fault]` button that issues `faultReset` with `severity=None`. Collapses
to nothing when the HCD is `Ready` and there is no error message.

### Architecture

The HCD uses three independent TCP connections to the controller (all on the same port —
the DMC-500x0 assigns each to one of its 8 Ethernet handles internally). All three actors
are spawned as siblings directly under `GalilHcd` — none is a child of another:

| Actor | Connection | Role |
|-------|-----------|------|
| `ControllerCommandActor` | command socket | SendCommand, ExecuteProgram, HaltExecution, init-time `TC 1`, thread allocation |
| `ControllerStatusActor` | status socket | QR polling, per-thread `MG _XQ<n>`, `MG ae[]` reads, AI polling, runtime `TC 1` |
| `ControllerConsoleActor` | console socket | Unsolicited MG output via `CF I` (hardware only) |

Each actor reports its connection status to `InternalStateActor` on startup via
`ReportConnectionStatus`. `HcdState.isOperational` is true when both command and status
connections are `Connected` (console is informational and does not affect readiness).

This means QR/AI polls never contend with command traffic at either the socket or actor-mailbox level.

The internal actors that orchestrate the above:

- **InternalStateActor** — central state repository with two notification channels:
  `StateChanged` (HCD + AxisState) and `CmdStateChanged` (AxisCmdState only — used by
  CommandWatchers). Owns the `EnterFaulted` transition logic.
- **CommandHandlerActor** — dispatches CSW commands; spawns one `CommandWatcherActor`
  per long-running command.
- **CommandWatcherActor** — subscribes to `CmdStateChanged` for its axis and
  `StateChanged` for the HCD; reports completion / failure / timeout to the CRM.
- **CurrentStatePublisherActor** — publishes CSW CurrentState events for HCD and
  per-axis updates.

The browser-side HMI infrastructure:

- **HmiServer** (Pekko HTTP) — WebSocket `/ws/state`, REST `POST /api/command`, `GET/POST /api/loglevel`
- **HmiJsonProtocol** — Serializes `HcdState` to JSON
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
| InternalStateActorTest | 63 | State management, pub/sub, motorPosition/motorDemand/angularPosition, ConnectionStatus, `EnterFaulted` transitions (Homing→Lost, Moving/Tracking→Error, activeCommand clearing, idempotency) |
| ControllerStatusActorTest | 25 | QR polling, adaptive rate, analog input polling, `_XQ<n>` per-thread synthesis (authoritative over stale QR `threadStatus`), `ae[axis]` interpretation (codes 2/3/4 → POSERR/LIMSWI/MCTIME, S55 `NotifyAxisHalted` pruning) |
| CommandHandlerActorTest | 16 | Immediate commands, validation, faultReset gating |
| CommandWatcherActorTest | 15 | Completion mask evaluation |
| LongRunningCommandTest | 24 | Motion command handlers |
| RotatingMechanismTest | 26 | Approach algorithm, positionWheel, offsetAxis, no-cpr fallback |
| AxisStateValidationTest | 13 | State machine rules, interruption mechanics, `stopCompletionState(homed)` |
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
# Against lab hardware:
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
| InternalStateActorTest | 63 | None |
| ControllerStatusActorTest | 25 | None |
| CommandHandlerActorTest | 16 | None |
| CommandWatcherActorTest | 15 | None |
| LongRunningCommandTest | 29 | None |
| RotatingMechanismTest | 26 | None |
| AxisStateValidationTest | 14 | None |
| IOTest | 17 | None |
| ControllerCommandActorTest | 16 | Hardware or Simulator (no CSW services) |
| CurrentStatePublisherActorTest | 4 | Simulator (no CSW services) |
| HcdIntegrationTest | 18 | Hardware or Simulator + FrameworkTestKit (no csw-services) |
| GalilSimulatorActorTest | 73 | None |
| **Total** | **325** | |

The HCD depends on `galil-io` for the controller wire protocol. That module has its own unit-test suite (`galil-io/GalilIoTest`, 45 tests) covering `writeRaw` / `send` (single + compound) / `sendAndWaitForPrompt` / `downloadProgram` / `uploadProgram` (including the DL `?`-rejection path and read-timeout save/restore) / `chunkCompound` and the 80-character line guard.  Run with `sbt "galil-io/test"`.
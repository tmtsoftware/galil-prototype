# Galil Simulator

Simulates a Galil DMC-500x0 motion controller, enabling the full HCD integration
test suite (`HcdIntegrationTest` — 13 tests) to run without hardware. The simulator
also supports standalone Assembly and Sequencer development against a simulated HCD,
and interactive testing via the REPL client.

## Overview

The simulator is a TCP server that speaks Galil protocol — CR/LF delimited ASCII
commands with `:` prompt acknowledgments and binary QR DataRecord responses. It
maintains per-axis simulated state (position, velocity, motor on/off, moving/jogging,
homed) and emulates motion by stepping axes toward their targets in a 10ms timer loop.

Thread management mirrors real hardware: `XQ #Label,N` sets bit N in the `_NO`
thread status bitmask, and the bit clears when the simulated program completes.
This is the mechanism the HCD's CommandWatcher uses to detect command completion, so
getting it right is essential for integration test fidelity.

The simulator identifies itself as `DMC50040 Rev 1.2sim` in response to the `ID`
command, allowing the HCD to distinguish simulated vs. real controllers if needed.

## Components

**GalilSimulatorActor** is the core: a stateful Pekko actor that processes every
Galil command, manages the `SimState` (axis states, thread bitmask, embedded
variables), runs the motion tick timer, and builds QR DataRecords from current state.

**GalilSimulator** wraps the actor in a TCP server using Pekko Streams. It handles
connection management, Galil protocol framing (CR/LF delimiters, semicolon-separated
multi-commands), and the `TH` (Tell Handles) command which requires connection
awareness.

**GalilSimulatorApp** is the standalone entry point for `sbt "galil-simulator/run"`.

## Supported Commands

**Identity & diagnostics:** ID, TC (error code), TH (network handles)

**QR DataRecord:** Returns a binary DataRecord with header, GeneralState (thread
status, I/O, error code), and per-axis GalilAxisStatus (status word with moving/
motor-off/PA-mode/direction bits, switches byte with stepper-mode and homed bits,
position, velocity encoded at 64x, stop code).

**Axis query commands** — all support both single-axis (`TPA`) and all-axis (`TP`)
forms. The all-axis form returns comma-separated values matching real Galil behavior:

| Command | Single-axis | All-axis example |
|---------|-------------|------------------|
| TP | Encoder position | `100, 200, 0, 0` |
| TD | Step/auxiliary position | `100, 200, 0, 0` |
| TV | Current velocity | `0.0000, 10000.0000, 0.0000, 0.0000` |
| SC | Stop code | `1, 1, 0, 0` |
| TS | Switches byte | `3, 3, 1, 1` |

TS returns bit 0 (stepper mode) + bit 1 (homed), matching real hardware output.

**Embedded program execution:** XQ dispatches by label — `#Init`, `#SetupX`,
`#HomeX`, `#MoveX`, `#StopX`, `#TrackX`, `#SelectX`. Each sets the thread bit
in `_NO`, performs its simulated action, and schedules thread completion. HX halts
a thread immediately.

**Axis configuration:** SP (speed), AC (acceleration), DC (deceleration), MT (motor
type), and other generic axis commands support both set (`SPA=10000`) and query
(`SPA?`) syntax.

**Motor control:** SH (servo here / motor on), MO (motor off), ST (stop motion),
BG (begin motion), JG (jog speed), DP (define position), RP (report position).

**Embedded variables:** Both array (`dmd[0]=500`, `MG dmd[0]`) and scalar
(`tcon=1000`, `MG tcon`) variables are supported. The `MG` command also handles
`_NO`, `_TDA`/`_TPA`, `TIME`, `_TM`, and `@AN[n]`.

**Variable inspection:**

| Command | Description | Example output |
|---------|-------------|----------------|
| LV | List scalar variables with values | `tcon= 1000.0000` |
| LA | List array names with dimensions | `speed[2]`, `dmd[2]` |

LV shows only scalar variables (no `[` in name), matching real Galil behavior.
LA groups array variables by base name and reports dimensions.

**Program transfer:** UL (upload from controller), DL (download to controller).

**I/O:** SB, CB, AO, CN — acknowledged with `:` but not functionally simulated.

## Motion Emulation

The motion loop ticks every 10ms. For position moves (PA mode), each axis advances
toward its demand at the configured speed (`speed[]` variable). When the remaining
distance falls below 0.5 counts, the axis snaps to the target, sets `stopCode=1`,
and clears the associated move thread. For jog moves (JG mode / tracking), the axis
moves at constant velocity indefinitely until stopped.

The `#StopX` handler is critical: it sets `moving=false` on the axis *and* clears
the move thread that was driving it. Without this, the move thread leaks permanently
because `advanceMotion` will never reach the target to clear it naturally. The `ST`
and `MO` commands perform the same cleanup.

## Running the Simulator

```bash
# Start on default 127.0.0.1:8888
sbt "galil-simulator/run"

# Then in another terminal, run HCD integration tests against it
unset CLUSTER_SEEDS
sbt -Dgalil.config.path=GalilHcdConfig-Simulator.conf "galil-hcd/testOnly *HcdIntegrationTest"
```

The simulator can also be used with the REPL client for interactive testing:

```bash
# In one terminal:
sbt "galil-simulator/run"

# In another:
sbt "galil-repl/run"
```

Example REPL session:

```
:XQ #Init,0
:XQ #SetupA,1; XQ #SetupB,2
:dmd[0]=500;dmd[1]=1000;XQ #MoveA,1; XQ #MoveB,2
:TP
500, 1000, 0, 0
:SC
1, 1, 0, 0
:LA
Atarget[2]
Btarget[2]
accel[2]
dmd[2]
speed[2]
:q
```

## Tests

### Unit Tests

The `GalilSimulatorActorTest` suite tests the actor directly via Pekko `ActorTestKit`,
with no TCP server, no simulator process, and no external dependencies. Each test
spawns a fresh actor and communicates through the `Command` message protocol.

```bash
sbt "galil-simulator/testOnly *GalilSimulatorActorTest"
```

64 tests in ~8s covering:

| Category | Tests | What's verified |
|----------|------:|-----------------|
| Identity & parsing | 4 | ID response, empty command, TC, unhandled commands |
| Embedded variables | 3 | Set/get, multi-variable independence, unset defaults |
| MG system queries | 4 | _NO, _TDA, TIME, @AN |
| Motor on/off & axis cmds | 4 | SH/MO via QR status bits, SP/AC set+query, DP/RP |
| Thread management | 4 | XQ sets bits, completion clears, multi-thread, HX |
| #Init | 1 | Default variable initialization |
| #Setup | 1 | Stepper config, motor off |
| #Home | 1 | Position reset, motor enable |
| #Move | 4 | Moving state, target reached + thread cleared, negative direction, concurrent axes |
| #Stop / ST / MO | 3 | Thread leak fix via #StopX, ST all-axes, MO cleanup |
| #Track | 2 | Jog start + thread release, position change over time |
| QR DataRecord | 7 | Header/axis count, position, velocity 64x, threadStatus, stepper bit, PA mode bit, negative direction bit |
| Program upload/download | 2 | UL/DL protocol |
| Edge cases | 3 | Zero-distance move, sequential moves, sample counter increment |
| JG/BG direct commands | 2 | Jog speed set/query, begin motion |
| Direct queries (TP/TD/TV/SC/TS) | 14 | Single-axis, all-axis comma-separated, homed bit, motion reflection |
| LV / LA | 5 | Scalar-only filtering, array dimensions, empty state |

### Legacy Integration Test

The `GalilIoTests` suite predates the enhanced simulator and tests basic TCP
connectivity and DataRecord round-trip parsing. Requires the simulator to be
running externally.

```bash
# Terminal 1: sbt "galil-simulator/run"
# Terminal 2:
sbt "galil-simulator/testOnly *GalilIoTests"
```

### Test Summary

| Suite | Tests | Dependencies |
|-------|------:|-------------|
| GalilSimulatorActorTest | 64 | None |
| GalilIoTests | 2 | Simulator running |
| **Total** | **66** | |
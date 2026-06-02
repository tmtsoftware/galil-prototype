# Galil Simulator

Simulates a Galil DMC-500x0 motion controller, enabling the full HCD integration
test suite (`HcdIntegrationTest` — 17 tests) to run without hardware. The simulator
also supports standalone Assembly and Sequencer development against a simulated HCD,
and interactive testing via the REPL client.

## Overview

The simulator is a TCP server that speaks Galil protocol — CR/LF delimited ASCII
commands with `:` prompt acknowledgments and binary QR DataRecord responses. It
maintains per-axis simulated state (position, velocity, motor on/off, moving/jogging,
homed) and emulates motion by stepping axes toward their targets in a 10ms timer loop.

The simulator covers 8 axes (A-H) — sufficient for both the lab DMC-50040 (4-axis)
and the STB DMC-4080 (8-channel, 7 active) configurations. It identifies itself as
`DMC50080 Rev 1.2sim` in response to the textual `ID` command. The simulator does
**not** implement the real controller's `^R^V` (`\u0012\u0016`) byte sequence;
instead, the HCD's `simulate = true` mode skips `identifyController()` entirely and
the HCD treats the simulator as an 8-axis controller by configuration.

The TCP front-end accepts multiple simultaneous connections (`Pekko Streams TCP`
server with `connections.runForeach`), so the HCD's three-connection architecture
(command / status / console) works against the simulator just as it does against
real hardware.

Thread management mirrors real hardware: `XQ #Label,N` sets bit N in the `_NO`
thread status bitmask, and the bit clears when the simulated program completes.
Per-thread state is queryable via `MG _XQ<n>` (returns the executing line number,
or `-1` if the thread has stopped) — the same authoritative source the HCD uses
to detect command completion in the presence of CMDERR-aborted threads.

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

**Identity & diagnostics:** `ID` (firmware identification — returns the
`DMC50080 Rev 1.2sim` response shown above; the real controller's `^R^V` byte-pair
is **not** handled, but in `simulate=true` mode the HCD skips that probe entirely),
TC (error code), TH (network handles).

**QR DataRecord:** Returns a binary DataRecord with header, GeneralState (thread
status, I/O, error code), and per-axis GalilAxisStatus (status word with moving/
motor-off/PA-mode/direction bits, switches byte with stepper-mode and homed bits,
position, velocity encoded at 64x, stop code).

**Axis query commands** — all support both single-axis (`TPA`) and all-axis (`TP`)
forms. The all-axis form returns 8 comma-separated values matching real Galil
behavior on the 8-axis simulator:

| Command | Single-axis | All-axis example (8 axes) |
|---------|-------------|---------------------------|
| TP | Encoder position | `100, 200, 0, 0, 0, 0, 0, 0` |
| TD | Step/auxiliary position | `100, 200, 0, 0, 0, 0, 0, 0` |
| TV | Current velocity | `0.0000, 10000.0000, 0.0000, ...` |
| SC | Stop code | `1, 1, 0, 0, 0, 0, 0, 0` |
| TS | Switches byte | `3, 3, 1, 1, 1, 1, 1, 1` |

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
(`tcon=1000`, `MG tcon`) variables are supported. Compound `MG arg1,arg2,...`
queries are split on commas and dispatched per-arg, with results joined
space-separated — this lets the HCD batch reads like `MG ae[0],ae[1],ae[2]` or
`MG _XQ1,_XQ2,_XQ3` into a single round-trip.

The `MG` command handles:

- `_NO` — thread status bitmask
- `_XQ<n>` — per-thread state (1.0 if thread N's bit is set in `_NO`, -1.0 otherwise);
  bare `_XQ` (no number) returns -1
- `_TDA` / `_TPA` — axis position
- `TIME`, `_TM` — controller time
- `@AN[n]` — analog inputs (returns `2.5000` baseline for all 8 channels)
- Scalar and array variables by name

**Variable inspection:**

| Command | Description | Example output |
|---------|-------------|----------------|
| LV | List scalar variables with values | `tcon= 1000.0000` |
| LA | List array names with dimensions | `speed[8]`, `dmd[8]` |

LV shows only scalar variables (no `[` in name), matching real Galil behavior.
LA groups array variables by base name and reports dimensions.

**Program transfer:** UL (upload from controller), DL (download to controller).

**I/O:** SB, CB toggle digital output bits (reflected in QR `digitalOutputs`).
AO, CN — acknowledged with `:` but not functionally simulated.

### `ae[]` Per-Axis Error Lifecycle

The simulator models the HCD's `ae[]` per-axis error code convention so that
`ControllerStatusActor` can be exercised end-to-end:

- `XQ #Label,N` for axis-affecting labels (`#Move`, `#Home`, `#Stop`, `#Setup`,
  `#Track`, `#Select`) sets `ae[idx]=1` on entry and records the thread → axis
  mapping in `_threadAxis[N]`.
- On the success path, `completeThread` (or `advanceMotion` arrival, which runs
  first) clears `ae[idx]=0`.
- Forced-termination paths — `MO`, `HX`, and the `#StopX` leaked-thread cleanup —
  also clear `ae[idx]=0` and prune `_threadAxis[N]`.

This gives the HCD a faithful approximation of the real controller's behavior when
combined with `_XQ<n>` queries, including the per-thread CMDERR semantics.

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
500, 1000, 0, 0, 0, 0, 0, 0
:SC
1, 1, 0, 0, 0, 0, 0, 0
:MG _XQ1,_XQ2
-1.0000 -1.0000
:LA
Atarget[8]
Btarget[8]
accel[8]
ae[8]
dmd[8]
speed[8]
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

73 tests in ~8s covering:

| Category | Tests | What's verified |
|----------|------:|-----------------|
| Identity & parsing | 4 | DMC50080 ID response, empty command, TC, unhandled commands |
| Embedded variables | 3 | Set/get, multi-variable independence, unset defaults |
| MG system queries | 4 | _NO, _TDA, TIME, @AN |
| Motor on/off & axis cmds | 4 | SH/MO via QR status bits, SP/AC set+query, DP |
| Thread management | 4 | XQ sets bits, completion clears, multi-thread, HX |
| #Init | 1 | Default variable initialization |
| #Setup | 1 | Stepper config, motor off |
| #Home | 1 | Position reset, motor enable |
| #Move | 4 | Moving state, target reached + thread cleared, negative direction, concurrent axes |
| #Stop / ST / MO | 3 | Thread leak fix via #StopX, ST all-axes, MO cleanup |
| #Track | 2 | Jog start + thread release, position change over time |
| QR DataRecord | 8 | Header/axis count, position, velocity 64x, threadStatus, stepper bit, PA mode bit, jog clears PA bit, negative direction bit |
| Program upload/download | 2 | UL/DL protocol |
| Edge cases | 3 | Zero-distance move, sequential moves, sample counter increment |
| JG/BG direct commands | 2 | Jog speed set/query, begin motion |
| Direct queries (TP/TD/TV/SC/TS) | 13 | Single-axis, all-axis comma-separated, homed bit, motion reflection, axis B |
| LV / LA | 5 | Scalar-only filtering, array dimensions, empty state |
| Digital I/O (SB/CB) | 6 | Set/clear bit, byte/bit mapping, accumulation, isolation, bits 9-16 |
| Analog inputs (`MG @AN`) | 3 | Numeric for all 8 channels, 2.5V baseline, compound query (`MG @AN[1],@AN[2],...`) |

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
| GalilSimulatorActorTest | 73 | None |
| GalilIoTests | 2 | Simulator running |
| **Total** | **75** | |
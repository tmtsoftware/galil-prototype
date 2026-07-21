# Assembly-level load testing (`AssemblyLoadApp`) — S84

A direct-`CommandService` load generator that drives every APS-ICS assembly to a
real, distinct operational target **all at once**, to exercise the GalilMotion
HCDs under the simultaneous, multi-axis, multi-assembly load the sequencer must
be able to produce — the load that surfaced the S82 stuck-Homing
thread-reservation race.

It drives assemblies the way the sequencer does: resolve each from the Location
Service, submit `Setup`s via `CommandService`. It does **not** go through the
ESW gateway (that is the browser/UI path), so the HCD / assembly / controller
concurrency is the only thing under test. Lives in `galil-client` beside
`TrackInjectorApp`; no `build.sbt` change.

## What "load" means here

Not re-homing. A **target set** commands every assembly to a *different*
operational target simultaneously — a chosen filter, a pupil mask, an absolute
focus position, a K-Mirror angle — so they run for different durations and
finish at staggered times. Then the next target set sends the whole instrument
somewhere else, and so on. The sequencer will not normally move everything at
once; this checks the system tolerates it when it does. (Simulators finish
faster than real controllers, so target distances are varied to spread out
completion times.)

## Command / target map (from the generated `*Keys` + SDD configs)

| Assembly (ctrl) | move command | target pool |
|---|---|---|
| PSH/PIT/APT FocusStage (1,1,3) | `positionFocusStage` ABSOLUTE `value` mm | ±50/±80/30 mm |
| STIM InsertionStage (2) | `positionStage` ABSOLUTE `value` mm | ±50/±80/30 mm |
| PSH/PIT FilterWheel (1) | `selectFilter` `filter` | F890N,F891N,F850M,F750W,F810N,F630N,F865N |
| APT FilterWheel (3) | `selectFilter` `filter` | ND1,ND2,NB589,OPEN |
| PSH PupilMaskWheel (1) | `selectPupilMask` `pupilMask` | PH-2-0,SH-0,SH-2,SH-5,Clear |
| PIT PupilMaskWheel (1) | `selectPupilMask` `pupilMask` | PH-1-1,Clear |
| FOC KMirror (3) | `positionKMirror` ABSOLUTE `positionValue` deg (MANUAL) | 45/−45/120/−90° |
| FOC TiltPlate (3) | `positionTiltPlate` ABSOLUTE `xValue`,`yValue` mm | 4 XY pairs (2 axes at once) |
| FOC SteeringBeamSplitter (2) | `positionBeamSplitter` ABSOLUTE `xValue`,`yValue` mm | 4 XY pairs |
| FOC CollimatorUnit (2) | `positionFrontAxis`/`positionRearAxis` (alternated) `positionValue` mm | ±50/±80/30 mm |
| FOC CalibrationSourceStage (2) | `setSlot` `slotNumber` | 1..5 |
| STIM FiberSourceStage (4) | `positionSource` ABSOLUTE X/Y/Z mm | 4 XYZ triples (3 axes at once) |
| STIM PupilMaskStage (4) | `positionMaskStage` ABSOLUTE X/Y mm, Phi deg | 4 XY-Phi triples (3 axes at once) |

Linear axes are ±100 mm in every config (default 0), so these absolute targets
are always in range. The multi-axis commands (`positionTiltPlate`,
`positionSource`, `positionMaskStage`, `positionBeamSplitter`) drive all of an
assembly's axes in **one** command — the within-assembly simultaneous-axis case
on controllers 3/4. Keys are built locally by name+type (the
`WheelAssemblyHandlers` technique), so the harness needs no dependency on
ics-assemblies.

## Why fan-out width, not depth (validation gates)

From `MotionAssemblyHandlers.validateCommand`:

- **PROCESSING**: every command except `stop` is rejected. You cannot pipeline a
  single assembly — contention comes from firing width-wise across the set. A
  target set is therefore one command per assembly, awaited as a **barrier**
  before the next set, so no assembly is over-driven.
- **PRE_HOMED**: only `configure`/`home` accepted — hence the configure+home
  warm-up before any target set.
- **`stop`** penetrates the PROCESSING gate (S79 SDD §6.1.3.3.2 exemption).

## Contention gradient (`IcsAssembliesContainer.conf`)

| Controller | Assemblies | Stresses |
|---:|---:|---|
| 1 | 6 single-axis (PSH+PIT focus/filter/pupil) | **max cross-assembly** thread-reservation grab (S82) |
| 4 | 2 × 3-axis (FiberSource, PupilMask) | **max within-assembly** `submitAllAxes` fan-out |
| 2 | 4 single-axis | moderate |
| 3 | 4 incl. TiltPlate (2-axis) + KMirror | mixed |

`--controllers 1` is the six-way cross-assembly primitive; `--controllers 4` the
multi-axis primitive; default `all` is the whole-instrument "can it move
everything at once" test.

## Scenarios

| `--scenario` | Behaviour |
|---|---|
| `list` | Resolve + print the selected set, each assembly's move command and target count; exit. |
| `configure-home` | Barrier `configure` wave, then barrier `home` wave (warm-up only). |
| `targets` | Warm up, then cycle target sets until `--duration` — each set sends every assembly to its next distinct target, barrier per set. The workhorse. `--overdrive` drops the barrier and paces at `--cadence-hz` (over-drives the PROCESSING gate; expect gate Invalids, tallied separately). |
| `stop-storm` | Fire a target set (no barrier), wait `--stop-delay-ms`, then a barrier `stop` wave; repeat. Stop under active motion. |
| `stop-idle` | Warm up (axes settle idle at home), then repeated barrier `stop` waves on **idle** axes. **The S82 regression** — see below. |

## The S82 regression (`stop-idle`) and the simulator

The S82 stuck-Homing race needed a program that completes *within one QR scan*
(sub-scan completion): a thread got reused before the scan attributed its
completion, clobbering the thread→axis registry. On real hardware, a `stop` on
an idle axis (`STx`/`#StopX`) is exactly such a sub-scan program.

The simulator previously applied the same 250 ms `ProgramCompleteDelay` to
`#StopX` as to homes/moves, which **mis-modelled** stop and closed the sub-scan
window on the sim. **S84 change** (`GalilSimulatorActor.scala`): `#StopX` now
completes at `StopCompleteDelay = 30 ms` (< the 100 ms action-rate scan), while
homes/moves/setups/tracks keep the ≥1-scan `ProgramCompleteDelay`. This matches
reality (only stop finishes sub-scan) and makes `stop-idle` a faithful,
deterministic reproduction of the S82 regime **on the simulator** — where the
reservation gate must hold. On the STB it was already realistic.

> The simulator and HCD test suites should be re-run after this change. The
> existing `#StopA` tests sleep ≥600 ms after the stop, comfortably above 30 ms,
> so they remain valid; re-run to confirm.

Expected on a healthy `stop-idle` run: the `registered threads` polling-rate
reason in the HCD logs, and **no** `INVARIANT VIOLATION` / registry-clobber.

## Prerequisites

1. `csw-services` running (Location Service).
2. GalilMotion HCDs running and registered.
3. `IcsAssembliesContainer` running and registered.

Order matters (services → HCDs → assemblies); after any services restart:

```
curl -s localhost:7654/location/list | tr ',' '\n' | grep GalilMotion
```

## Examples

```
# What would be targeted on controller 1, and with what commands
sbt "galil-client/runMain csw.proto.galil.client.AssemblyLoadApp \
     --scenario list --controllers 1"

# Six-way contention on controller 1 for two minutes, CSV out
sbt "galil-client/runMain csw.proto.galil.client.AssemblyLoadApp \
     --scenario targets --controllers 1 --duration 120 --report /tmp/c1.csv"

# Whole instrument to varied targets, all at once, for five minutes
sbt "galil-client/runMain csw.proto.galil.client.AssemblyLoadApp \
     --scenario targets --duration 300 --report /tmp/all.csv"

# S82 regression — stop bursts on idle axes (sim, post stop-fast change; or STB)
sbt "galil-client/runMain csw.proto.galil.client.AssemblyLoadApp \
     --scenario stop-idle --controllers 1 --duration 60"

# Over-drive the PROCESSING gate deliberately (characterise rejects)
sbt "galil-client/runMain csw.proto.galil.client.AssemblyLoadApp \
     --scenario targets --controllers 1 --duration 60 --overdrive --cadence-hz 5"
```

## Pass / fail — two places, together

### 1. The run report (stdout + optional `--report` CSV)

- **`Completed`** — success.
- **`Invalid(...)`** — almost always an *expected* gate/validation reject
  (over-driving a PROCESSING assembly; a move sent while PRE_HOMED). Counted
  separately.
- **`Cancelled`** — *expected* under `stop-storm`: a `stop` interrupting an
  in-flight move yields `Cancelled` on that move. Its own headline category, not
  a finding.
- **`suspect`** = `Error` / `Future-failed` / `Locked` / `Started` — what to
  investigate; listed per command with example assemblies.
- Per-command **latency** (min/p50/p90/p99/max) over completed commands.
  Controller-1 barrier waves should show the longest tails.

### 2. HCD process-log scrape (`$TMT_LOG_HOME`)

The concurrency defects surface in the HCD logs, not the command responses:

```
cd "$TMT_LOG_HOME"
grep -nE 'INVARIANT VIOLATION|registry|CMDERR|TC [0-9]|underrun' <hcd-logs>
```

Expect **none**: `INVARIANT VIOLATION` / registry-clobber ERROR (the S82 class),
`CMDERR` / `TC <n>`, PVT `underrun`. Expect to **see** (healthy): the
`registered threads` polling reason on `stop-idle`; every assembly's
`status`/`axisStatus` back to `IDLE`/`OPERATIONAL` at quiescence; alarms Okay.
Over a soak, also watch JVM heap/GC/thread/fd on the HCD, assembly-container,
and sim processes.

## CPU load (REQ-2-APS-0621)

Separate from the concurrency load above: every run also measures the **CPU load the APS
software imposes**, to verify REQ-2-APS-0621 (*"the CPU load … shall not exceed 70%"*). At
start-up the app subscribes to the per-JVM **`cpuLoad`** events — one per HCD
(`APS.ICS.HCD.GalilMotion.{1..4}`) plus the single assembly container
(`APS.ICS.IcsAssemblies`) — collects them for the whole run, then prints a CPU section:

- **Per-JVM** (one row per OS process / pid): `processCpuLoad` mean/max and `systemCpuLoad`
  max, as percentages. The `role` column labels each JVM (`HCD.GalilMotion.N`, or
  `ICS assemblies (shared JVM)`).
- **Per-host aggregate**: the SUM over distinct JVM pids of `processCpuLoad`, in 1 s bins,
  as mean / p95 / max with a **`[PASS|FAIL vs 70% max]`** verdict. This is the REQ-2-APS-0621
  number — it sums the *raw* fractions per distinct pid, so there is no per-component
  double-count. Whole-machine `system%` is shown as context (it includes non-APS load —
  Redis, the harness JVM, the OS).

`cpuLoad` is per-**process** (JDK `getProcessCpuLoad`, already normalised to the whole
machine), so the unit is the JVM, not the CSW component — the assembly container is one row,
not sixteen. Stale retained events from dead JVMs (the event service keeps the last value per
key) are dropped by an age filter (>5 s old) and reported as `ignored N stale …`. With
`--report`, the raw samples are also written to `<report>.cpu.csv`.

Caveats: the dev Mac is not representative deployment hardware, and the harness / Redis /
Location Service are excluded from the APS sum (not deployed APS components). For meaningful
statistics use a longer scenario (e.g. `--scenario targets --duration 120`); `configure-home`
is only a few seconds, so ~1 sample per JVM.

**Operational note:** the CPU section reports samples collected *asynchronously* — events
flow Redis → subscription stream → callback into a local queue, and the report reads that
queue (not a live query). On a very short scenario (`configure-home`, ~2 s) the subscription
can be torn down at scenario end before samples arrive; the harness settles briefly before
teardown, but a `--duration` scenario (e.g. `--scenario targets --duration 120`) is the
reliable way to collect samples. (Separately, the event service retains the last value per
key, so a dead JVM replays a stale `cpuLoad` on subscribe — these are dropped by the >5 s age
filter and reported as `ignored N stale …`.)

## Not yet covered (future extensions)

- Named `selectSource` (SKY/STIMULUS) for InsertionStage and the light-source
  commands (`setSourceIntensity`, `setOptic`) — motion targets are used today.
- K-Mirror `setMode`/tracking load (kept in MANUAL here to stay positionable).
- Automatic quiescence assertion by subscribing to each assembly's status event
  (today a manual log/UI check).
- A gateway-driven variant, if UI-scale load is ever wanted — the wave/metrics
  core is driver-agnostic.

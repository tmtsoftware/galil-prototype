# ics-assemblies

CSW **Assemblies** for the APS Instrument Control System (ICS), built on top of the
GalilMotion **HCD** (`galil-hcd`). Each assembly exposes a high-level, CSW-ICD command
interface for one APS motion mechanism and translates those commands into HCD commands.

This module implements the **complete set of 16 APS motion assemblies** across the APT,
FO&C, PIT, PSH, and STIM mechanism groups — from simple linear stages through indexed
filter/pupil-mask wheels to the continuously-rotating, TCS-tracking **K-Mirror** — all on
a shared `MotionAssemblyHandlers` base. A standalone **TCS PupilRotation simulator** is
included so the K-Mirror (the only assembly with an external-subsystem dependency) can be
exercised without a real TCS.

- **Stack:** Scala 3.6.4, Apache Pekko, CSW 6.0.0, sbt multi-module, Java 21.
- **Depends on:** `galil-hcd` (`compile->compile`) for the generated HCD command/event
  keys (`csw.proto.galil.GalilMotionKeys`).
- **Paired UI:** `aps-ics-ui` provides a command/monitor panel for every assembly here.

---

## Layering

```
   CSW client / Sequencer / Engineering UI (aps-ics-ui)
            │  CSW ICD: configure, home, the mechanism commands, stop, abortErrorRecovery
            ▼
   ┌──────────────────────────────────────┐
   │  <Group><Mechanism>Handlers          │  concrete assembly (e.g. PshFilterWheelHandlers,
   │  extends one of the shared bases      │  FocKMirrorHandlers) — assembly-specific
   │                                        │  commands + telemetry
   └──────────────────────────────────────┘
            │  common base: lifecycle, HCD tracking, state machine, recovery, telemetry
            ▼
   ┌──────────────────────────────────────┐
   │  GalilMotion HCD (galil-hcd)         │  one HCD per Galil controller
   └──────────────────────────────────────┘
            │  Galil command set (counts; HCD resolves deg↔counts for rotating axes)
            ▼
   Galil DMC-500x0 controller (or simulator)
```

### Base-class hierarchy

All concrete assemblies extend a CSW `ComponentHandlers` subclass. The shared behaviour
(CSW lifecycle, HCD location tracking, the CurrentState subscription, the
operational/command state machine, the HCD-fault alarm, per-command HCD-wait timeouts,
and the stop-then-resend error-recovery routine) lives in `MotionAssemblyHandlers`:

```
ComponentHandlers (CSW)
└── MotionAssemblyHandlers              common base for every ICS assembly
    ├── StageAssemblyHandlers           linear / single-axis positioning stages
    ├── WheelAssemblyHandlers           indexed wheels (slot selection)
    │   └── PupilMaskWheelAssemblyHandlers   pupil-mask wheels
    └── (FocKMirrorHandlers)            extends MotionAssemblyHandlers directly —
                                        continuous rotation + TCS slewing/tracking
```

**Unit ownership.** The HCD speaks in controller **counts** (and resolves degrees↔counts
for rotating axes). An assembly works in user units and converts to counts before
commanding the HCD: **mm** for linear stages, **degrees** for rotating wheels and the
K-Mirror (`countsPerMm` / `countsPerDegree` per axis). Clients never reason about counts
or controller-internal frames.

---

## Assembly inventory

16 assemblies across 5 mechanism groups, distributed over 4 Galil controllers per SDD
Figure 2-2. CSW prefixes are `aps.ICS.<GROUP>.<Mechanism>`.

| Group | Mechanism | CSW prefix | Type | HCD |
|-------|-----------|------------|------|-----|
| **APT** | Focus Stage | `aps.ICS.APT.FocusStage` | linear stage | GalilMotion.3 |
| **APT** | Filter Wheel | `aps.ICS.APT.FilterWheel` | filter wheel | GalilMotion.3 |
| **FOC** | Steering Beam Splitter Stage | `aps.ICS.FOC.SteeringBeamSplitterStage` | linear stage | GalilMotion.2 |
| **FOC** | Collimator Unit | `aps.ICS.FOC.CollimatorUnit` | linear stage | GalilMotion.2 |
| **FOC** | Calibration Source Stage | `aps.ICS.FOC.CalibrationSourceStage` | linear stage | GalilMotion.2 |
| **FOC** | Tilt Plate | `aps.ICS.FOC.TiltPlate` | stage (2-axis) | GalilMotion.3 |
| **FOC** | K-Mirror | `aps.ICS.FOC.KMirror` | rotating + tracking | GalilMotion.3 |
| **PIT** | Focus Stage | `aps.ICS.PIT.FocusStage` | linear stage | GalilMotion.1 |
| **PIT** | Filter Wheel | `aps.ICS.PIT.FilterWheel` | filter wheel | GalilMotion.1 |
| **PIT** | Pupil Mask Wheel | `aps.ICS.PIT.PupilMaskWheel` | pupil-mask wheel | GalilMotion.1 |
| **PSH** | Focus Stage | `aps.ICS.PSH.FocusStage` | linear stage | GalilMotion.1 |
| **PSH** | Filter Wheel | `aps.ICS.PSH.FilterWheel` | filter wheel | GalilMotion.1 |
| **PSH** | Pupil Mask Wheel | `aps.ICS.PSH.PupilMaskWheel` | pupil-mask wheel | GalilMotion.1 |
| **STIM** | Insertion Stage | `aps.ICS.STIM.InsertionStage` | linear stage | GalilMotion.2 |
| **STIM** | Fiber Source Stage | `aps.ICS.STIM.FiberSourceStage` | linear stage | GalilMotion.4 |
| **STIM** | Pupil Mask Stage | `aps.ICS.STIM.PupilMaskStage` | stage (incl. rotational φ) | GalilMotion.4 |

Each assembly's source lives under `src/main/scala/aps/ics/assembly/<group>/`, its ICD
keys under `assembly/icd/`, and its HOCON config under `src/main/resources/`.

---

## Module layout

```
ics-assemblies/
├── README.md                          this file
└── src/main/
    ├── scala/aps/ics/
    │   ├── assembly/
    │   │   ├── common/                shared bases + model
    │   │   │   ├── AssemblyModel.scala            AxisConfig, AxisSnapshot, HcdLifecycle,
    │   │   │   │                                  OperationalState + CommandState enums
    │   │   │   ├── MotionAssemblyHandlers.scala   common base (lifecycle, HCD tracking,
    │   │   │   │                                  state machine, recovery, telemetry scaffold)
    │   │   │   ├── StageAssemblyHandlers.scala    linear-stage base
    │   │   │   ├── WheelAssemblyHandlers.scala    indexed-wheel base
    │   │   │   └── PupilMaskWheelAssemblyHandlers.scala
    │   │   ├── icd/                    generated CSW ICD keys, one <Mechanism>Keys.scala each
    │   │   ├── apt/  foc/  pit/  psh/  stim/      concrete assemblies, grouped by subsystem
    │   │   │     (foc/ also holds KMirrorTrackingControlActor.scala)
    │   │   └── IcsContainerApp.scala   combined-container launcher
    │   └── sim/
    │       ├── TcsPupilRotation.scala       shared TCS PupilRotation event contract
    │       └── TcsPupilRotationSimApp.scala  standalone TCS simulator (slew / track modes)
    └── resources/
        ├── <Mechanism>.conf            per-axis + assembly config
        ├── <Mechanism>Container.conf   single-assembly CSW container
        ├── IcsAssembliesContainer.conf combined container — every assembly in one JVM
        ├── InsertionStageAlarms.conf   ASCF defining the hcdFaulted alarm
        └── application.conf            logging only
```

---

## State model

Two independent state variables are projected from what the HCD reports and published in
the `status` event.

**Operational state** (`OperationalState`) — long-lived readiness:

| State         | Meaning |
|---------------|---------|
| `PreHomed`    | HCD connected & configured, axis not yet homed; only `configure`/`home` accepted |
| `Operational` | axes homed and ready (SDD prose calls this "Ready") |
| `Degraded`    | reserved for multi-axis partial readiness |
| `Faulted`     | the HCD controller is Faulted (or not connected); all commands rejected |

Operational state is a **continuous projection** of the HCD, in both directions: it is
not a one-time latch. On restart against an already-homed controller the assembly comes
up `PreHomed` and self-promotes to `Operational` once the HCD's `homed` is observed — no
forced re-home. When the HCD returns to Ready after a fault, the assembly leaves
`Faulted` on its own.

**Command state** (`CommandState`) — the in-flight command:

| State           | Meaning |
|-----------------|---------|
| `Idle`          | no command in progress |
| `Processing`    | a command is executing (new submits rejected) |
| `ErrorRecovery` | a command failed and a recovery attempt is running (only `abortErrorRecovery` accepted) |
| `Failed`        | the last command failed and recovery (if any) did not succeed |

---

## Error handling

The recovery machinery lives in the base, so every assembly inherits it. The assembly
recovers **its own axis** and never tries to recover a shared resource. Two distinct
error classes, projected from what the HCD reports:

### 1. HCD controller Fault — alarm + Faulted, no recovery

When the HCD lifecycle reports `Faulted`, the assembly raises the **`hcdFaulted`** alarm
(Major) and transitions to `Faulted`. It makes **no recovery attempt** — a faulted
controller may serve several assemblies, and they must not all try to recover it at once.
The assembly only *mirrors* the controller: when the HCD returns to Ready (cleared
elsewhere, e.g. via the HCD HMI), the assembly clears the alarm and leaves `Faulted`.
There is intentionally **no** assembly-level `faultReset` command.

> The `hcdFaulted` alarm only annunciates if the Alarm Service is running and the alarm is
> loaded (see *Running*). Without it, `setSeverity` fails gracefully and is logged — the
> assembly still goes `Faulted`; only the alarm annunciation is absent.

### 2. Command failure — per-command recovery routine

When a motion command is accepted but **fails to reach its demand** (the HCD returns
`Error`), the assembly runs a single recovery attempt:

```
commandState → ErrorRecovery
  → stop the axis            (stop's completion is the "axis back to idle" gate)
  → resend the command once  (the same demand)
      → Completed  ⇒ command Completed,  commandState → Idle
      → Error      ⇒ command Failed,     commandState → Failed
```

Recovery is gated on `Error` specifically. Deterministic rejections are **not** retried:
`Invalid` (soft-limit / wrong-state / missing-key — caught at validation), `Cancelled`
(our own abort), and `Locked` (another client owns the component). `configure` and `stop`
are never wrapped in recovery.

`recover(runId, dispatch)` is a `protected` overridable hook — the extension point for
future per-error-type intelligence. The default is stop-then-resend.

**Idempotent resends.** Because recovery resends the original demand, every command must
be idempotent on resend. Absolute moves and `home` already are; a `RELATIVE` positioning
demand is therefore resolved to an **absolute** target at intake (`current + delta`, read
while the axis is idle) so a resend reaches the same position rather than re-offsetting.

### 3. abortErrorRecovery — halt an in-progress recovery

`abortErrorRecovery` is handled out-of-band: it sets an abort flag and best-effort-stops
the axis, returning `Completed` immediately. The running recovery observes the flag and
resolves the original command as `Cancelled` → `commandState = Idle` (ready again, not a
latched failure). It is only meaningful while `commandState = ErrorRecovery`.

---

## Telemetry

Published via the CSW Event Service (requires `csw-services … -e`), throttled to ~1 Hz
while online and ~30 s while offline:

- **`status`** — `assemblyState` (OperationalState), `hcdState` (HCD lifecycle),
  `commandState`. Wheel and K-Mirror assemblies add their mode/selection fields (e.g. the
  K-Mirror's `mode`, `slewModeState`, `trackingModeState`).
- **`axisStatus`** — `axisState`, `position` (mm for stages, degrees for rotating),
  `velocity`, `indexed` (homed), `inPosition`; wheels add achieved slot/angle.
- **K-Mirror tracking events** — `trackingMetrics` (per cycle) and `trackingError` (on
  entering the error state). See below.

---

## K-Mirror tracking (SDD §8)

The K-Mirror (`aps.ICS.FOC.KMirror`) is the one assembly that depends on an external
subsystem (TCS) and the only continuously-tracking mechanism. It extends
`MotionAssemblyHandlers` directly (single continuous rotating axis) and adds a long-lived
child actor, **`KMirrorTrackingControlActor`**, that subscribes to the TCS PupilRotation
event and drives the HCD according to the operating mode (`setMode`):

- **MANUAL** — diagnostic positioning (`positionKMirror`), no TCS-driven motion.
- **SLEWING** — pre-stage to the predicted track-start angle via a single HCD
  `positionAxis`; `slewModeState` reaches `SLEW_COMPLETE` when in position, which gates
  `setMode(TRACKING)`.
- **TRACKING** — stream HCD `trackAxis(position, rate, validTime)` from the live TCS
  demand and run the `trackingModeState` convergence machine
  (`NOT_CONVERGED → CONVERGED → …_WITH_PIT`, plus `TRACKING_ERROR`). The PIT-loop
  correction (`updatePitCorrectionOffset`, ~every 10 s) is folded into the demand once the
  loop is running; `restartTracking` drops back to non-PIT tracking.

Demand (SDD §8.2.2.2): `rotation + maskRotationOffset + thirdTerm`, where the third term
is the static PIT-to-PSH offset (non-PIT) or the live PIT-loop correction (PIT in use).
`trackAxis` for a rotating axis takes **degrees / deg-per-sec** (the HCD owns the
conversion to counts and the PVT / shortest-arc math).

### TCS PupilRotation simulator

`TcsPupilRotationSimApp` publishes the `TCS.PointingKernel.PupilRotation` event so the
K-Mirror can be exercised without a real TCS. Both of its modes are driven by the same
parallactic-angle model and hour angle, so the slew pre-stage and the track stream agree
on the start position (no jump at the SLEWING→TRACKING handoff):

```bash
# Pair with the K-Mirror in SLEWING: constant predicted start angle q(HA0)
sbt "ics-assemblies/runMain aps.ics.sim.TcsPupilRotationSimApp --mode slew"

# Pair with the K-Mirror in TRACKING: live parallactic stream from HA0
#   --time-scale accelerates the (slow) sidereal field-rotation rate for testing
sbt "ics-assemblies/runMain aps.ics.sim.TcsPupilRotationSimApp --mode track --time-scale 200"
```

Args: `--mode slew|track`, `--ha0 <deg>` (default 30), `--dec <deg>`, `--latitude <deg>`,
`--time-scale <factor>`, `--rate-hz <hz>`, `--lead <sec>`.

> **Time frame.** `validTime` is a TAI instant; the HCD resolves it against TAI now and
> converts to UTC only at display boundaries. Send a proper `TAITime` (the sim does).

---

## Configuration

Per assembly, HOCON files under `src/main/resources` (loaded into the CSW Configuration
Service for bring-up):

- **`<Mechanism>.conf`** — per-axis config in user units (the assembly converts to counts):
  soft limits, default position, `galilHcd`/`galilChannel` binding, motion parameters, and
  any mechanism-specific named positions / choices. **Bring-up values are deliberately
  made-up and internally consistent — not calibrated.**
- **`<Mechanism>Container.conf`** — a single-assembly CSW container (top-level key is
  `name`, *not* `prefix`): the component and its HCD connection.
- **`IcsAssembliesContainer.conf`** — the combined container declaring all 16 assemblies and
  their HCD connections, so they start and stop together.
- **`InsertionStageAlarms.conf`** — ASCF defining the `hcdFaulted` alarm.

> **Config Service wins.** The active Config Service version overrides bundled resource
> files; always re-seed after editing a `.conf` (see `scripts/load-config.sh`).

---

## Build

```bash
sbt ics-assemblies/compile        # compile this module
sbt ics-assemblies/stage          # produce target/universal/stage/bin/ics-assemblies
sbt stage                         # stage everything (HCD + assemblies + client)
```

The module is wired into the root build and depends on `galil-hcd`.

---

## Running (simulator bring-up)

Start CSW services first (Location + Event required; add Alarm to exercise `hcdFaulted`):

```bash
csw-services start -e            # add -a for the Alarm Service
```

**Seed the Configuration Service** with the assembly configs (idempotent):

```bash
MODE=update ./scripts/load-config.sh
```

**Start the GalilMotion HCD(s).** The full set spans controllers 1–4; for a single-group
bring-up start just the controller(s) that group needs (see the inventory). Example,
controller 1 (PSH/PIT):

```bash
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd1.conf \
  -Dgalil.config.path=GalilHcdConfig-APS-1.conf
```

**Start the assemblies.** Either the combined container (all 16 in one JVM)…

```bash
./target/universal/stage/bin/ics-assemblies -main aps.ics.assembly.IcsContainerApp
```

…or a single assembly via its container conf:

```bash
./target/universal/stage/bin/ics-assemblies \
  -main aps.ics.assembly.IcsContainerApp \
  --local ics-assemblies/src/main/resources/PshFilterWheelContainer.conf
```

Each assembly locates its HCD, runs a startup `configure`, and projects its operational
state from the HCD (`PreHomed`, or `Operational` if the HCD is already homed).

> The deployment helper scripts (`apsIcsHcdSims.sh`, `apsIcsHcds.sh`) start/stop the
> four-controller / four-HCD set together; each hardware HCD serves its HMI on
> `9090 + controller.id`.

### To load the hcdFaulted alarm (optional)

```bash
csw-services start -e -a
csw-admin-cli init ics-assemblies/src/main/resources/InsertionStageAlarms.conf --local
```

---

## Adding a new motion assembly

1. Generate `<Mechanism>Keys.scala` for its CSW ICD (commands + events) under `icd/`
   (prepend `package aps.ics.assembly.icd` to icd-db output).
2. Add `<Group><Mechanism>Handlers` under the group package (`apt/`, `foc/`, …), extending
   the right base: `StageAssemblyHandlers` (linear), `WheelAssemblyHandlers` (indexed
   wheel), `PupilMaskWheelAssemblyHandlers`, or `MotionAssemblyHandlers` directly for a
   bespoke mechanism. Override `configResource` / `axisConfigKeys`,
   `validateSpecificCommand` / `handleSpecificCommand` (resolve relative demands to
   absolute), and `publishTelemetry`; optionally override `recover`.
3. Add `<Mechanism>.conf` and `<Mechanism>Container.conf` (and an ASCF if alarms are used).
4. **Wire it in (four points):**
   1. register the assembly in `IcsAssembliesContainer.conf`;
   2. add its config to `scripts/load-config.sh`;
   3. add its descriptor + import to the UI `registry.tsx`;
   4. make its entry live in the UI `ComponentSelector.tsx`.
5. Seed the config (`MODE=update ./scripts/load-config.sh`) and verify no
   "No component is registered" errors.

The base supplies configure / home / moveToDefaultPosition / stop, HCD location tracking,
the CurrentState subscription, the operational/command state machine, the HCD-fault alarm,
per-command HCD-wait timeouts, and the error-recovery routine.

---

## Known limitations / open items

- **HCD reconnect without reconfigure.** If the HCD drops and reconnects, the assembly
  does not currently re-apply `configure` (a one-time startup guard). To be addressed.
- **Recovery is a single stop-then-resend** for all error types. The `recover` hook is in
  place; per-error-type "recipe" intelligence (stop vs home by cause, retry counts per SDD
  Table 6-2) is future work.
- **Provisional calibration values.** Config values (soft limits, motion parameters,
  offsets such as the K-Mirror `maskRotationOffset` / `trackingInPositionThreshold`, the
  Tilt Plate stage→M1 factors) are bring-up placeholders, not calibrated against the RDs.
- **TCS contract is provisional.** The `TCS.PointingKernel.PupilRotation` schema used by
  the K-Mirror is a stand-in until the real TCS schema is published.
- **`GalilMotionKeys` lives in `galil-hcd`.** Lifting the shared HCD ICD keys to a common
  module would let assemblies depend on the ICD without the full HCD.
# ics-assemblies

CSW **Assemblies** for the APS Instrument Control System (ICS), built on top of the
GalilMotion **HCD** (`galil-hcd`). Each assembly exposes a high-level, CSW-ICD command
interface for one APS motion mechanism and translates those commands into HCD
commands.

The first assembly implemented here is the **Stimulus Insertion Stage**
(`APS.ICS.STIM.InsertionStage`, SDD §6.9). The module is structured so the remaining
APS motion assemblies (K-Mirror, LGSF beam steering, FO&C mechanisms, …) reuse a
common base.

- **Stack:** Scala 3.6.4, Apache Pekko, CSW 6.0.0, sbt multi-module, Java 21.
- **Depends on:** `galil-hcd` (`compile->compile`) for the generated HCD command/event
  keys (`csw.proto.galil.GalilMotionKeys`).

---

## Layering

```
   CSW client / Sequencer
            │  (CSW ICD: configure, home, selectSource, positionStage, stop, abortErrorRecovery)
            ▼
   ┌─────────────────────────────┐
   │  <Mechanism>Handlers        │   concrete assembly (e.g. InsertionStageHandlers)
   │  extends StageAssemblyHandlers │  – assembly-specific commands + telemetry
   └─────────────────────────────┘
            │  (common base: lifecycle, HCD tracking, state machine, recovery)
            ▼
   ┌─────────────────────────────┐
   │  GalilMotion HCD (galil-hcd) │   one HCD per Galil controller
   └─────────────────────────────┘
            │  (Galil command set)
            ▼
   Galil DMC-500x0 controller (or simulator)
```

**Unit ownership.** The HCD speaks only in controller **counts** (and resolves
degrees↔counts for rotating axes). An assembly works in user units (**mm** for a
linear stage) and converts mm↔counts via a per-axis `countsPerMm` before commanding
the HCD. Clients never reason about counts or controller-internal frames.

---

## Module layout

```
ics-assemblies/
├── README.md                         this file
└── src/main/
    ├── scala/aps/ics/assembly/
    │   ├── common/
    │   │   ├── AssemblyModel.scala         AxisConfig, AxisSnapshot, HcdLifecycle,
    │   │   │                               OperationalState + CommandState enums
    │   │   └── StageAssemblyHandlers.scala  abstract base: lifecycle, HCD location
    │   │                                    tracking, CurrentState subscription,
    │   │                                    state projection, alarm, validation gate,
    │   │                                    command dispatch + error recovery
    │   ├── icd/
    │   │   └── InsertionStageKeys.scala     generated command/event keys for the
    │   │                                    InsertionStage CSW ICD
    │   └── insertionstage/
    │       ├── InsertionStageHandlers.scala concrete assembly + `InsertionStageApp`
    │       │                                (container entry point)
    │       └── InsertionStageClientApp.scala command-line client (submit + watch)
    └── resources/
        ├── InsertionStage.conf            axis + assembly config (mm-based)
        ├── InsertionStageContainer.conf   CSW container (component + HCD connection)
        ├── InsertionStageAlarms.conf       ASCF defining the hcdFaulted alarm
        └── application.conf                logging only (see note under "Client")
```

---

## State model

Two independent state variables are projected from what the HCD reports and published
in the `status` event.

**Operational state** (`OperationalState`) — long-lived readiness:

| State         | Meaning |
|---------------|---------|
| `PreHomed`    | HCD connected & configured, axis not yet homed; only `configure`/`home` accepted |
| `Operational` | axis homed and ready (SDD prose calls this "Ready") |
| `Degraded`    | reserved for multi-axis partial readiness (unused by the single-axis InsertionStage) |
| `Faulted`     | the HCD controller is Faulted (or not connected); all commands rejected |

Operational state is a **continuous projection** of the HCD, in both directions: it is
not a one-time latch. On restart against an already-homed controller the assembly comes
up `PreHomed` and self-promotes to `Operational` once the HCD's `homed` is observed —
no forced re-home. When the HCD returns to Ready after a fault, the assembly leaves
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

The assembly recovers **its own axis** and never tries to recover a shared resource.
Two distinct error classes, projected from what the HCD reports:

### 1. HCD controller Fault — alarm + Faulted, no recovery

When the HCD lifecycle reports `Faulted`, the assembly raises the **`hcdFaulted`**
alarm (Major) and transitions to `Faulted`. It makes **no recovery attempt** — a faulted
controller may serve several assemblies, and they must not all try to recover it at
once. The assembly only *mirrors* the controller: when the HCD returns to Ready (cleared
elsewhere, e.g. via the HCD HMI), the assembly clears the alarm and leaves `Faulted`.
There is intentionally **no** assembly-level `faultReset` command.

> The `hcdFaulted` alarm only annunciates if the Alarm Service is running and the alarm
> is loaded (see *Running*). Without it, `setSeverity` fails gracefully and is logged —
> the assembly still goes `Faulted`; only the alarm annunciation is absent.

### 2. Command failure — per-command recovery routine

When a motion command is accepted but **fails to reach its demand** (the HCD returns
`Error` — an axis going to `error` state, an interrupted move, an underrun, …), the
assembly runs a single recovery attempt:

```
commandState → ErrorRecovery
  → stop the axis            (stop's completion is the "axis back to idle" gate)
  → resend the command once  (the same demand)
      → Completed  ⇒ command Completed,  commandState → Idle
      → Error      ⇒ command Failed,     commandState → Failed
```

Recovery is gated on `Error` specifically. Deterministic rejections are **not** retried:
`Invalid` (soft-limit / wrong-state / missing-key — caught at validation and never
dispatched), `Cancelled` (our own abort), and `Locked` (another client owns the
component). `configure` and `stop` are never wrapped in recovery.

`recover(runId, dispatch)` is a `protected` overridable hook — the extension point for
future per-error-type intelligence (e.g. choose *home* vs *stop* by error type). The
default is stop-then-resend.

**Idempotent resends.** Because recovery resends the original demand, every command must
be idempotent on resend. Absolute moves and `home` already are. A `positionStage RELATIVE`
demand is therefore resolved to an **absolute** target at intake (`current + delta`,
read while the axis is idle) and issued as an absolute `positionAxis`, so a resend
reaches the same demanded position rather than re-offsetting from wherever the axis
stopped.

### 3. abortErrorRecovery — halt an in-progress recovery

`abortErrorRecovery` is handled out-of-band: it sets an abort flag and best-effort-stops
the axis, returning `Completed` immediately without disturbing the in-flight command's
state. The running recovery observes the flag (after its own stop, or after its resend
is interrupted) and resolves the original command as `Cancelled` → `commandState = Idle`
(ready again, not a latched failure). It is only meaningful while `commandState =
ErrorRecovery`; the validation gate rejects it otherwise.

---

## Telemetry

Published via the CSW Event Service (requires `csw-services … -e`), throttled to ~1 Hz
while online and ~30 s while offline:

- **`status`** — `assemblyState` (OperationalState), `hcdState` (HCD lifecycle),
  `commandState`.
- **`axisStatus`** — `axisState`, `position` (mm), `velocity`, `indexed` (homed),
  `inPosition`.

---

## Configuration

Three HOCON files under `src/main/resources` (loaded locally for bring-up; SDD §6.1.4.1
will move these to the CSW Configuration Service):

- **`InsertionStage.conf`** — per-axis config in **mm** (the assembly converts to counts
  via `countsPerMm`): soft limits, default position, `galilHcd`/`galilChannel` binding,
  motion parameters (`velocity`, `acceleration`, `deceleration`, `indexOffset`,
  `indexSpeed`, `inPositionThreshold`), and InsertionStage-specific named positions
  (`stimulusPosition`, `skyPosition`). **Bring-up values are deliberately made-up and
  internally consistent — not calibrated.**
- **`InsertionStageContainer.conf`** — the CSW container. Top-level key is **`name`**
  (ContainerInfo) — *not* `prefix`. Declares the component
  (`aps.ICS.STIM.InsertionStage`, `RegisterAndTrackServices`) and its HCD connection
  (`aps.ICS.HCD.GalilMotion.2`, which must match `stage.galilHcd`).
- **`InsertionStageAlarms.conf`** — ASCF defining the `hcdFaulted` alarm; its
  `prefix`+`name` must match the `AlarmKey` in `StageAssemblyHandlers`.

Controller binding follows SDD Figure 2-2: the Insertion Stage shares **controller 2**
with the FO&C mechanisms (it injects into the FO&C path), provisionally on channel `A`.

---

## Build

```bash
sbt ics-assemblies/compile        # compile this module
sbt ics-assemblies/stage          # produce target/universal/stage/bin/ics-assemblies
sbt stage                         # stage everything (HCD + assemblies + client)
```

The module is wired into the root build's `aggregatedProjects` (so `sbt compile`/`stage`
include it) and depends on `galil-hcd`.

---

## Running (simulator bring-up)

Four terminals. Start CSW services first.

**1. CSW services** — Location + Event are required; add Alarm only to exercise
`hcdFaulted`:

```bash
csw-services start -e            # Location + Event (Redis). Add -a for the Alarm Service.
```

`csw-services` runs in the foreground; Ctrl-C stops everything. Restarting it cycles the
Location Service, so the HCD and assembly must be restarted too.

**2. GalilMotion HCD** (simulated controller 2):

```bash
./target/universal/stage/bin/galil-hcd \
  -main csw.proto.galil.hcd.GalilHcdApp \
  --local galil-hcd/src/main/resources/GalilHcd2.conf \
  -Dgalil.config.path=GalilHcdConfig-APS-2.conf
```

**3. InsertionStage assembly:**

```bash
./target/universal/stage/bin/ics-assemblies \
  -main aps.ics.assembly.stim.StimInsertionStageApp \
  --local ics-assemblies/src/main/resources/InsertionStageContainer.conf
```

The assembly locates the HCD, runs a startup `configure`, and projects its operational
state from the HCD (`PreHomed`, or `Operational` if the HCD is already homed).

**4. Client / watch** (staged binary, or `sbt "ics-assemblies/runMain …"`):

```bash
# live telemetry
./target/universal/stage/bin/ics-assemblies \
  -main aps.ics.assembly.stim.StimInsertionStageClientApp watch

# a command
./target/universal/stage/bin/ics-assemblies \
  -main aps.ics.assembly.stim.StimInsertionStageClientApp positionStage ABSOLUTE 60
```

> Two main classes share the staged `ics-assemblies` script, so `-main <FQCN>` is
> required. With `sbt runMain` the working dir is the module dir, so use a
> module-relative `--local` path; the staged binary is run from the repo root and uses
> repo-relative paths.

### To load the hcdFaulted alarm (optional)

```bash
csw-services start -e -a
csw-admin-cli init ics-assemblies/src/main/resources/InsertionStageAlarms.conf --local
```

---

## Client CLI

`InsertionStageClientApp [--assembly <name>] <command> [args]`
(default assembly: `ICS.STIM.InsertionStage`)

| Command | Args | Notes |
|---------|------|-------|
| `configure` | | re-apply axis config |
| `home` | | home the axis (→ Operational) |
| `moveToDefault` | | move to the configured default position |
| `selectSource` | `SKY` \| `STIMULUS` | move to the named source position |
| `positionStage` | `ABSOLUTE` \| `RELATIVE` `<mm>` | RELATIVE resolved to absolute under the hood |
| `stop` | | stop the axis |
| `abortErrorRecovery` | | halt an in-progress recovery (valid only while `ErrorRecovery`) |
| `watch` | | subscribe to `status` + `axisStatus` until Ctrl-C |

> The client builds its own ActorSystem with a Pekko Artery remoting overlay (scoped to
> the client) so `submitAndWait` can message the remote assembly. The assembly itself
> gets networking from the CSW framework — `application.conf` must **not** force
> `pekko.actor.provider = remote`.

---

## Adding a new motion assembly

1. Generate `<Mechanism>Keys.scala` for its CSW ICD (commands + events) under `icd/`.
2. Add `<Mechanism>Handlers extends StageAssemblyHandlers` under a new package:
   - override `configResource` and `axisConfigKeys`;
   - override `validateSpecificCommand` and `handleSpecificCommand` (return a resolved
     thunk — resolve any relative demand to absolute at build time);
   - override `publishTelemetry` to publish the mechanism's events;
   - optionally override `recover` for mechanism-specific recovery intelligence.
3. Add `<Mechanism>.conf`, `<Mechanism>Container.conf`, and (if alarms are used) an ASCF.
4. Add an `object <Mechanism>App` container entry point.

The base supplies configure / home / moveToDefaultPosition / stop, HCD location
tracking, the CurrentState subscription, the operational/command state machine, the
HCD-fault alarm, per-command HCD-wait timeouts, and the error-recovery routine.

---

## Known limitations / open items

- **Single simulator instance.** The HCD HMI binds port 9090; running multiple simulated
  HCDs needs distinct HMI ports. Scaling to many simulated ICS mechanisms needs this
  resolved.
- **HCD reconnect without reconfigure.** If the HCD drops and reconnects, the assembly
  does not currently re-apply `configure` (a one-time startup guard). To be addressed.
- **Recovery is a single stop-then-resend** for all error types. The `recover` hook is
  in place; the per-error-type "recipe" intelligence (stop vs home by cause, retry
  counts per SDD Table 6-2) is future work, driven from sequencer-level robustness needs.
- **`GalilMotionKeys` lives in `galil-hcd`.** Lifting the shared HCD ICD keys to a common
  module would let assemblies depend on the ICD without the full HCD.
- **Deploy.** `ics-assemblies` is not yet wired into `galil-deploy` for HostConfig-based
  deployment.

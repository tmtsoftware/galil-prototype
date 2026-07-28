# ADR-002: Record axis position history at the scan boundary in the HCD

**Status:** Accepted (2026-07-27, S88)
**Date:** 2026-07-27 (S88)
**Deciders:** Angelic (aebbers)
**Origin:** Design discussion on adding a plotting capability to the GalilMotion HCD
engineering HMI; the discovery that the HMI's existing position chart had never
rendered on any network; and the subsequent investigation of what the ICS actually
archives.

---

## Context

### What was already there, and why nobody knew

`resources/web/index.html` has carried a `PositionChart` component since the initial
HMI development -- a 150 px Recharts `LineChart` over all active axes, rendered
unconditionally in the main layout. It had never displayed data. Two independent
faults, either one sufficient to hide it completely:

1. **The sampler never fired.** The 2 Hz history recorder listed `state` in its
   `useEffect` dependency array. `state` receives a fresh object reference on every
   `stateUpdate` frame, and `HmiServer` pushes those on a fixed 250 ms scheduler
   (`wsUpdateInterval`, 4 Hz). The 500 ms interval was therefore cleared and re-armed
   every 250 ms and never reached its own deadline. `history` stayed empty and
   `PositionChart`'s `history.length < 3` guard returned `null`.

2. **The charting library never loaded.** The Recharts 2.x UMD build declares
   `prop-types` as an *external*, not a bundled dependency -- its factory tail is
   `t.Recharts = e(t.React, t.PropTypes, t.ReactDOM)`. The page loaded only `react`
   and `react-dom`, so the factory threw
   (`TypeError: Cannot read properties of undefined (reading 'oneOfType')` at
   `Animate.js:330`), the `Recharts` global was never assigned, and `hasRecharts`
   was permanently `false`.

Fault 2 is the more instructive one. The `hasRecharts` guard was written as graceful
degradation for an air-gapped network. What it actually did was convert a hard
dependency error into silence -- for the entire life of the feature, on every network.
A runtime capability probe cannot distinguish "this environment lacks the library"
from "we wired the library wrong."

Both faults were repaired in S88 in order to see what the original implementation
looked like. It renders, and it is adequate as a motion indicator, but it confirmed
the scaling problem below and it is not the right foundation.

### Why the client-side approach cannot be made good

The existing chart samples browser-side off `/ws/state`, three hops downstream of the
data. Every hop degrades the signal, worst where it matters:

- **Standby (1 Hz scan):** the 4 Hz push emits four frames per real sample. Three of
  every four plotted points are duplicates -- an artifact of the transport, not of the
  mechanism.
- **Action (10 Hz scan):** ~60 % of scans never reach the browser, and the 4 Hz push
  phase is uncorrelated with the scan phase, so surviving samples are unevenly spaced.
  This is the regime during motion -- the only regime anyone wants a plot for.
- **No time base.** The X axis is `tickRef.current`, a bare counter. A polling-rate
  transition, a comms stall, or a dropped scan are all invisible.
- **No history before the panel is opened,** and none across a reload. The reconnect
  path calls `window.location.reload()` by design (to clear stale React state after an
  HCD Restart), so browser-resident history is destroyed by exactly the event you would
  most want to inspect afterwards.

None of these are fixable in the frontend; they are properties of the sample point.

### What the ICS actually archives (investigated S88)

The initial rationale for this ADR leaned on "flight recorder" value. That framing was
challenged on the grounds that ICS intends to archive published state anyway, so the
investigation below was run before accepting the ADR. It changed the justification.

The chain from acquisition to archive has three stages, each lossy:

```
QR scan              CSP timer             assembly throttle
1 Hz standby     ->  10 Hz fixed       ->  1 Hz online / 0.033 Hz offline
10 Hz action         CurrentState          SystemEvent -> DMS archive
(real acquisition)   (component-local)     (the archived path)
```

- **HCD CurrentState is not the archived path.** Verified against csw v6.0.0:
  `CurrentStatePublisher.publish` sends `Publish(currentState)` to a component-local
  `PublisherMessage[CurrentState]` actor -- "published to the subscribed components."
  That is Pekko pub/sub to components calling `subscribeCurrentState`, not the Event
  Service. The ICD agrees: every `CurrentState*` item is marked `Archive: no`. The only
  Event Service publication in `galil-hcd` is `cpuLoad`.
- **The assemblies are the outward-facing publishers.** APS ICS SDD 6.1.1 and 6.1.5.5:
  stage assemblies subscribe to the HCD's CurrentState and Axis CurrentState events,
  and their Event Publisher Actor publishes assembly Status and per-axis Axis Status
  events over the CSW Event Service. SDD 14 Table 14-1: "All events are archived in DMS."
- **The archived axis position is ~1 Hz, decimated by discard.**
  `MotionAssemblyHandlers.throttledPublish()` is a leading-edge throttle at 1000 ms
  while online: it publishes the first update after each 1 s boundary and drops the
  rest. During a move at the 10 Hz scan rate, roughly nine of every ten real samples
  never reach DMS. A 200 ms transient lands in one archived sample or none.
- **The SDD is consistent with that.** Table 14-1 nominates `axisMotionMetrics`
  ("Time to move and motion distance", per axis, on command completion) as the intended
  mechanism for motion characterisation. The archive is designed around 1 Hz status
  plus on-occurrence metrics, not a dense trace.

**Conclusion: this recorder is not redundant with the archive; it is the only place a
dense position trace exists.** It is an order of magnitude denser than anything that
reaches the observatory tooling, in raw encoder counts at the controller-axis level --
below the engineering-unit, assembly-axis-named abstraction that DMS stores. The two
are complementary layers, not duplicates.

### The scaling problem

With all active axes sharing one Y axis in raw counts, a linear stage at ~200 000
counts and a wheel at ~4 000 counts cannot both be legible. On an 8-axis controller the
common case is several traces pinned flat against the bottom. Axis selection reduces
this but does not solve it -- any two axes of differing mechanism type reproduce it.

---

## Decision

**Record position history inside the HCD, sampled once per QR scan at the scan
boundary, into a bounded columnar ring buffer; deliver it to the HMI as incremental
WebSocket frames plus a REST backfill/export endpoint; render it as stacked per-axis
channels over a real time axis.**

### 1. Sample at the scan boundary, not per axis, not downstream

`ControllerStatusActor.handleQRResponse` establishes the right seam. It stamps a
wall-clock time (`System.currentTimeMillis()`, currently assigned to `lastPollTime`)
and a monotonic `observedAt` *before* iterating axis statuses and calling
`updateAxisState` per axis. One write is added after that loop, carrying all axes under
**one** timestamp.

Per-scan rather than per-axis matters:

- All axes in a sample share a single instant, so cross-axis timing comparison is
  meaningful rather than approximate.
- The buffer is columnar -- one `Array[Long]` of times, one `Array[Double]` per axis --
  making CSV export naturally aligned and the wire encoding compact.
- It is one call site, not eight.

**Timestamps are `System.currentTimeMillis()` (UTC), not TAI.** The plot's primary job
is correlation with the HMI log panel, whose lines carry CSW's UTC timestamps. This
matches the S86 precedent, where `cpuLoad`'s `eventTime` is `UTCTime` and the consumer's
age filter needs no offset. Anything requiring TAI alignment (PVT segment analysis)
should read `trackingSession`, which already carries TAI.

Note that this is the acquisition instant -- something no downstream consumer currently
has, because no published CurrentState carries a timestamp at all (see Deferred below).

### 2. Ownership: an instance, not a singleton

`GalilHcd.initialize()` constructs `ControllerStatusActor` (the writer) and `HmiServer`
(the reader). The buffer is created there and passed to both.

This deliberately does **not** follow `CpuLoadMonitor`'s JVM-singleton pattern (S86).
That pattern exists because CPU load is a per-JVM measurand and up to sixteen assemblies
share one JVM, so a `startOnce` latch is the only way to get one publisher. Position
history is per-controller with exactly one writer and one reader, both constructed by
the same parent. An instance keeps the state non-global and directly unit-testable.

### 3. This does not disturb ADR-001

ADR-001 established that IS is the single attribution authority and CS is a pure
observer. A history recorder is *observation*, which is CS's remit. The recorder derives
no state, makes no decisions, is write-only from CS's perspective, cannot influence
attribution or thread lifecycle, and has no failure mode that propagates -- a full or
failed buffer drops samples and nothing else.

The one real hazard is **blocking the scan thread**. The write must be O(1), allocation
free, and hold its lock only long enough to store primitives: a `synchronized` append
into preallocated arrays, ~10 writes/second, against a reader that copies under the same
lock. Stated as a constraint, not left to inference.

Because `updateAxisState` computes position from a stepper/servo branch
(`auxiliaryPosition` for steppers, `motorPosition` for servos), it returns that value to
the loop rather than the loop recomputing it. Recomputation would create a second source
of truth for "what position means" -- the sort of duplication ADR-001 exists to prevent.

### 4. Retention: 3000 samples per axis

Sized as 300 s at the 10 Hz action rate. Memory is negligible: 8 axes x 3000 x 8 B plus
a shared 3000 x 8 B time column -- under 220 KB.

**The window is sample-bounded, not time-bounded**, intentionally. At the 1 Hz standby
rate the same 3000 samples span ~50 minutes. History is cheapest exactly when the
mechanism is idle and longest when nothing is happening, which is the useful direction.
The consequence to accept is that the window's *duration* varies, so the plot must
always label its actual span rather than implying a fixed one.

Only **position** is recorded. `positionError` and `velocity` are present in the same
per-scan update and would cost one array each, but are out of scope here. The columnar
layout means adding a signal later is an added array and an added column in the
encoders, not a redesign.

### 5. Delivery: incremental frames, with REST for backfill and export

- **`positionSamples` WebSocket frame.** On each existing 4 Hz push tick, emit every
  sample recorded *since the previous tick*. This delivers full scan-rate fidelity
  **without raising the WebSocket frame rate**. Raising the push rate to 10 Hz was
  rejected: the `stateUpdate` frame is a complete state snapshot, and inflating a rate
  the entire page depends on to serve one trace is the wrong trade.
- **`GET /api/history`** -- columnar JSON over the retained window, so opening the panel
  shows history immediately. Also the recovery path for a client that missed frames.
- **`GET /api/history.csv`** -- the retained window as CSV.

Because the WebSocket is a single `BroadcastHub`, the delta cursor is server-global, not
per-client. Clients joining mid-stream reconcile via the REST backfill. Per-client
cursors would require restructuring the hub for no benefit at the scale of one or two
engineering clients.

### 6. Stacked channels, and vendored libraries

**Rendering:** one row per selected axis, each independently autoscaled with its own
units, all sharing one X domain -- a multi-channel scope. The only option that keeps
absolute counts readable across mechanism types; normalisation would make traces
comparable in shape at the cost of the Y axis meaning anything.

**Vendoring:** React, ReactDOM, `prop-types` and Recharts move into `resources/web/` and
are served by the HCD. This removes the internet dependency from a console that will
eventually run on observatory networks, and removes the failure class described in
Context -- a missing library becomes a missing file at build time, not a silent `null`
at render time.

---

## Consequences

**Gained**

- Full scan-rate fidelity with correct, uniform timestamps at the acquisition instant.
- The only dense position trace in the system. DMS receives ~1 Hz decimated position via
  the assembly throttle; this is ~10x denser during motion, in raw counts.
- History that exists *before* the panel is opened and survives the forced reload on
  reconnect.
- An instrument for two items on the section 10 horizon: the S65 STB axis-D PVT position
  freeze (whose failure signature is precisely a position trace) and PVT load testing.
- CSV export makes the HMI usable as a scope during STB hardware windows.
- Legible multi-axis display on a controller whose axes differ in scale by orders of
  magnitude.

**Costs and risks**

- A write on the QR scan path. Bounded by the O(1)/no-allocation/short-lock constraint
  above, but it is new work on a hot, latency-sensitive thread and should be reviewed
  as such.
- ~220 KB per HCD process, x4 in the standard four-controller deployment.
- Three new HTTP/WS surfaces on a server that is already unauthenticated and bypasses
  the gateway/AAS (S87). Read-only, exposing telemetry `/api/status` already exposes, so
  the posture is unchanged -- but that posture remains why the HMI is linked, not
  embedded.
- ~1 MB of vendored JavaScript enters the repository.
- The retained window's duration varies with polling rate (see section 4).

**Explicitly not done**

- No new controller traffic. The QR DataRecord already returns position for every axis
  every scan; a dedicated sampler was considered and rejected as a second read path for
  data the HCD already holds.
- No change to the QR polling rate or policy.
- No CSW event publication of position history. This is a per-process engineering
  diagnostic, consistent with the S86 decision not to declare assembly `cpuLoad` in
  icd-db while it remains a single-JVM diagnostic. Publishing history above the HCD
  would be an ICD change and a separate decision.
- `positionError` and `velocity` are not recorded (see section 4).

---

## Deferred findings (raised by this investigation, not addressed here)

These were surfaced while establishing the archive path. All three are independent of
this ADR and are deliberately left for separate work.

1. **`CurrentStateAxis[A-H]` is published at 10 Hz; the ICD shows "1.0 Hz *" and there
   is no way to correct it.** Investigated and closed in S88 with no model change.
   The sequence is recorded because the first two conclusions were wrong:
   (a) the published ICD renders "Max Rate 1.0 Hz *, Archive no" for every current
   state, which reads as a declared rate being violated 10x;
   (b) the `currentStates` entries in `ics/hcd.GalilMotion/publish-model.conf` in fact
   declare no `maxRate` and no `archive`, so "1.0 Hz *" is icd-db's DEFAULT rendering
   for an unspecified rate -- an omission, not a breach;
   (c) declaring one is not possible: `icd-db -i` rejects it outright with
   *"extraneous key [maxRate] is not permitted"* at `#/publish/currentStates/*`.
   `maxRate` is schema-valid only on an `event`.
   So the rate shown for a current state is a fixed artefact of icd-db, and a consumer
   reading 1 Hz off the ICD for `CurrentStateAxis` is misled by the tool, not by the
   model. Nothing to fix; worth knowing before anyone designs against that figure.
   (`archive` is moot for the same class of reason: `CurrentState` cannot reach the
   Event Service at all -- `EventPublisher.publish` takes `Event`, `CurrentState` is not
   one, and nothing in csw v6.0.0 bridges them.)

2. **No published CurrentState carried a sample timestamp.** HCD SDD CCR02 Table 41
   documents `lastPollingTime` ("Timestamp for the last status poll") as an internal
   variable, but Table 41 has no "Pub." column and no time parameter had reached the
   ICD, so assemblies could neither timestamp nor age-check HCD data. ADDRESSED in the
   model in S88: `sampleTime` (`type = taiTime`) added to `CurrentStateAxis[A-H]`,
   carrying the QR scan instant shared by every axis read in that scan. TAI rather than
   the UTC millis this ADR's buffer uses -- it matches `trackAxis.validTime` and the
   assembly `axisMotionMetrics` times, whereas the buffer's UTC stamp exists to line up
   against log timestamps. Description kept to one line ("Absolute TAI time at which the
   axis data was read.") to match the surrounding model style. Regenerating the keys and
   populating the parameter are still to do.

3. **Publication cadence is decoupled from acquisition.** `CurrentStatePublisherActor`
   resamples IS state on a fixed 10 Hz timer: ten identical publishes per real reading
   at standby, and an uncorrelated beat against the scan at action rate. Publishing on
   scan completion would make each published sample correspond to exactly one controller
   read -- a precondition for item 2 being meaningful. Blast radius checked: late
   subscribers still get data within 1 s (the scan always fires at >= 1 Hz), K-Mirror
   tracking convergence consumes CurrentState unthrottled but only while tracking, when
   the scan is already at 10 Hz, and the on-change `axisState` publish would still be
   needed between scans.

4. **Assembly axis status is throttled to 1 Hz -- and that is per spec.** RESOLVED in
   S88: the assembly models declare `maxRate = 1, archive = true` on `axisStatus`
   directly (e.g. `ics/apt.FocusStage/publish-model.conf`), so `throttledPublish()`
   matches the declared contract. Dense motion detail was never meant to ride
   `axisStatus`; SDD Table 14-1 assigns it to `axisMotionMetrics`, published per move.

5. **The assembly publishing design is substantially unimplemented (S88).** Declared in
   the models and generated keys but published nowhere in `ics-assemblies`:
   `axisConfig` (and every per-name variant), `axisMotionMetrics` (likewise),
   `startupMetrics`, and the detector `tempSetPointCommanded`/`tempSetPointReached`.
   `axisMotionMetrics` is the consequential one: it carries `motionCmdReceived`/
   `motionCmdCompleted` (TAI) and `motionStartPosition`/`motionEndPosition` (mm), and is
   the SDD's designated motion record -- so the archive currently holds no per-move
   detail at all. Treated as a prototype gap to close, not a spec question: the models
   already specify the parameter sets and the rates, and `axisConfig`'s own description
   states when it should fire ("published only when configAxis or configLinearAxis cmd
   has completed during assembly startup", `maxRate = 0.01`).

---

## Alternatives considered

**Fix the client-side recorder and keep sampling in the browser.** Done in S88 as a
diagnostic step, and it is what exposed fault 2. Rejected as the destination: capped at
4 Hz, aliased and duplicated depending on polling rate, no time base, destroyed by the
reload on every reconnect.

**Raise the WebSocket push rate to 10 Hz.** Rejected. The frame is a full state
snapshot; this inflates a rate the whole page depends on, for one trace. The
`positionSamples` delta frame achieves the same fidelity at the existing frame rate.

**A dedicated sampler polling the controller for the plot.** Rejected. It would add
controller traffic to re-read data the QR scan already returns each scan.

**Buffer in `InternalStateActor` instead of CS.** IS is where positions land, but IS is
the attribution authority under ADR-001, and adding telemetry retention dilutes exactly
the separation that ADR established. CS is already the pure observer; observation
belongs there.

**Match the published/archived series instead of the scan.** Considered once the archive
path was understood, on the theory that the engineering console should show what the
observatory tool shows. Rejected: the archived series is ~1 Hz decimated engineering
units under assembly axis names, a different abstraction level serving a different
purpose. Reproducing it here would duplicate what DMS already holds and discard the one
thing only the HCD can provide.

**Time-bounded rather than sample-bounded eviction.** Would give a constant-duration
window at the cost of discarding standby history that is free to keep. Rejected in
favour of labelling the actual span.

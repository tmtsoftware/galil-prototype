# ADR-001: Centralize thread-lifecycle attribution in InternalStateActor

**Status:** Accepted (2026-07-14, with Amendment A; verified by 2× stop-storm on the full sim stack — 1120 submits, 0 suspect outcomes)
**Date:** 2026-07-13 (S85)
**Deciders:** Angelic (aebbers)
**Origin:** `stop_storm` false-positive error latch on axis A (S85 log analysis); design
discussion following the S82/S84 thread-lifecycle work.

---

## Context

### The incident

During a `stop_storm` run (2026-07-13 18:24), axis A was latched to `axisState=Error`
with *"Embedded program ended unexpectedly without controller error"* — while its
`#StopA` program was healthy and completed cleanly 57 ms later. The forensic dump
shows the cause directly:

```
axisThreads=HashMap(B -> 2, A -> 4)   ← CS's replica: stale (MoveA's old thread)
aeValues=HashMap(… A -> 1 …)          ← the *running* StopA's entry flag
axesWithClearedThread=Set(A)          ← _XQ4 = -1 (MoveA finished normally)
```

`ControllerStatusActor` (CS) evaluated `ae[A]==1` against thread 4 (the completed
`#MoveA`) when axis A's live program was on thread 2 (`#StopA`, XQ'd 2 ms before the
scan). The `RegisterAxisThread(A, 2)` that would have refreshed CS's map was still in
flight from `InternalStateActor` (IS). No `HX` was involved — axis A was idle when
`stopAxis` arrived — so the `NotifyAxisHalted` synchronization point never ran.

### The structural cause

The logical relation *"which controller thread is running which axis's program"* is
materialized in three actors:

| Actor | State | Role |
|-------|-------|------|
| `ControllerCommandActor` (CI) | `unobservedThreads: Set[Int]` | Allocation/reservation gate (S82). Keyed by thread; no axis. **A genuinely distinct concern — out of scope here.** |
| `InternalStateActor` (IS) | `threadRegistry: Map[Int, Axis]` | **Authoritative** registry + per-axis state (`activeThread`, `axisState`, `axisErrorMsg`). |
| `ControllerStatusActor` (CS) | `axisThreads: Map[Axis, Int]` | Inverse-keyed **replica** of IS's registry, maintained by fire-and-forget forwarding (`RegisterAxisThread` / `ClearAxisThread` / `NotifyAxisHalted`). |

The defect generator is an **asymmetry in who attributes what**:

- **Completions** are attributed by IS against its authoritative registry, gated by
  `UpdateThreadStatus.observedThreads` so a scan that predates a registration can
  never complete a just-started command (S82).
- **Errors** (`ae[]` attribution, `decideAxisAndControllerErrors`) are attributed
  *locally in CS against its replica*, with **no freshness gate**. CS structurally
  cannot gate — it *is* the stale copy.

The `ae[]` protocol makes freshness load-bearing: every embedded program sets
`ae[axis]=1` at entry and clears it at clean exit (`galilHCD_STB.dmc` — "Flag to
detect if this program halts/errors before finishing"). So `ae==1` means *"running
OR died"*, and the only disambiguator is whether **the axis's current thread** is
still executing. Evaluate it against a stale thread and a healthy program reads as a
dead one — exactly the incident.

### The guard inventory (cost of the replica)

Every one of these exists solely to keep the replicated state coherent across the
async seams:

1. `observedThreads` gate on `UpdateThreadStatus` (IS ~159–167) — scan-predates-registration race, completions only.
2. Synchronous `NotifyAxisHalted` ask + prune (CH ~592–611, CS ~453–467) — HX residue race.
3. "Stale reverse mapping" drop in `handleRegisterThread` (IS ~897) — lost-exit recovery.
4. S82 `INVARIANT VIOLATION` clobber recovery (IS ~881) — reallocation-before-observation.
5. S84 `reuseHaltedThread` retention, safe only *because* guard 2 hides the thread across the HX→re-register gap.

Guards 2 and 5 encode "thread halted, awaiting reuse" as **absence from CS's map** —
implicit state, invisible in IS, unavailable to the error-attribution path. The
incident is the fifth distinct race in this seam's history (S55, S82 ×2, S84, S85);
each was patched with another guard. The guard count is proportional to
(state copies × update paths); reducing copies is the only move that shrinks it.

### Constraints

- QR scan is a tight synchronous I/O sequence (`QR → _XQ → ae[] → [TC]`) at up to
  10 Hz during motion; it must not block on another actor's mailbox.
- Motor safing on an unattributable controller error must remain prompt. (Note:
  defense in depth — the controller's own `#POSERR`/`#LIMSWI`/`#MCTIME` handlers
  provide hardware-level protection independent of the HCD.)
- Priority is long-term stability/reliability/maintainability over minimal diff
  (explicit direction, S85). The HCD is feature-complete; regression risk must be
  managed by tests and re-running the S84 load suite.

---

## Decision

**Make `InternalStateActor` the single attribution authority. Demote
`ControllerStatusActor` to a pure observer.**

CS gathers raw per-scan observations and ships them to IS in one message. IS —
owner of the authoritative registry — performs *both* completion and error
attribution under *one* freshness gate. Fault actions flow IS → CI, which is
already wired (`ReleaseThread`, and `safeAllMotors` is already a fire-and-forget
`SendCommand("ST;MO")` to CI; IS already owns `EnterFaulted`).

### New scan contract

CS's per-scan output becomes a single message (superseding `UpdateThreadStatus`):

```scala
case class ScanObservations(
  threadStatusByte: Int,          // _XQ-synthesized, as today
  observedThreads:  Set[Int],     // threads this scan actually queried
  aeValues:         Map[Axis, Int],
  errorCode:        Int,          // raw QR errorCode
  tcText:           Option[String] // eagerly fetched iff errorCode != 0 (see below)
) extends Command
```

### The attribution invariant (replaces the replica)

> IS attributes `ae[axis]==1` as a program failure **only when the thread currently
> registered for that axis (per `threadRegistry`) is in `observedThreads` AND its
> bit is clear AND the entry is not marked Halted.**

Replaying the incident under this rule: IS's registry says A→2 (registered at
44.077, *before* the scan's delivery). The stale scan observed threads {2, 4} with
thread 2 active (or, had CS's set been even staler and missed thread 2 entirely,
thread 2 unobserved). Either way A's *current* thread is not observed-cleared →
no error. The bug class is eliminated by construction: a stale CS query set can
cause a missed or superfluous `_XQ` query (self-healing next scan, ≤100 ms at
action rate), never a misattribution.

### What each actor keeps

- **CS** keeps: connection handling, QR parse, `_XQ` synthesis (the S53 stale-byte
  logic — an I/O concern, correctly placed), `ae[]`/`whlpos[]` reads, polling-rate
  policy, TC fetch, motor-safing execution *on request*. Its `axisThreads` map is
  replaced by an advisory `threadsToQuery: Set[Int]` used only to build the `_XQ`
  query and the polling-rate decision — **never as a decision input for attribution**.
- **IS** gains: `decideAxisAndControllerErrors` (rewritten against `threadRegistry`),
  `pendingControllerError` one-scan deferral state, `lastReportedAxisError` dedup,
  and an explicit **Halted** state on registry entries (see below). On an
  unattributable fault it calls its existing `EnterFaulted` and sends `ST;MO` to CI
  via its existing `commandActor` ref.
- **CI** is untouched. `unobservedThreads` is the allocation gate, keyed by thread,
  no axis — a different concern that happens to involve threads.

### Halted becomes explicit registry state

Today "halted, awaiting reuse" (S84) is encoded as *absence from CS's map* via
`NotifyAxisHalted`. Under this decision, `checkAndInterrupt`'s post-HX notification
redirects to IS — `ThreadHalted(thread, axis)` — which marks the registry entry
Halted: excluded from both completion and error attribution until re-registered
(reuse path, same thread → same axis, benign) or unregistered. The same
synchronization point (synchronous ask before launching the follow-on) is preserved;
only the owner changes — and the state becomes visible, logged, and testable instead
of being the shape of a hole in a replica.

### TC latch handling

Today CS defers TC consumption one scan so the hardware latch persists for the
retry. Under IS ownership the deferral state is in IS, so CS instead fetches TC
**eagerly** on the first `errorCode != 0` scan and carries the text in
`ScanObservations`; IS holds the pending text across its one-scan defer. The
controller-internal race this defer covers (errorCode latches before `_XQ` reports
−1) is unchanged and the defer is retained — in IS.

*Implementation caution:* verify the other TC 1 consumer (post-`#Init` check in
`GalilHcd`) cannot be raced by eager scan-fetch. Polling is suspended around
`#SetupX`/BZ, which should cover it — confirm before merging.

---

## Options Considered

### Option A — Status quo + one-scan deferral in CS's Step 3 (the minimal patch)

Apply the existing `pendingControllerError` pattern to the "ended unexpectedly"
path: report only if `(thread-cleared ∧ ae==1 ∧ errorCode==0)` persists two
consecutive scans. The transient evaporates when `RegisterAxisThread(A,2)` lands.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low (~30 lines + tests) |
| Regression risk | Low |
| Fixes incident | Yes |
| Fixes bug *class* | **No** — replica still makes decisions; sixth race arrives later |
| Guard inventory | Grows by one |
| Maintainability | Declines — one more temporal special case in a 78 KB actor |

**Pros:** shippable in a day; no message-protocol change; genuinely-dead programs
still detected (one scan later).
**Cons:** entrenches the asymmetry; every future lifecycle feature (S84-style) must
re-derive the replica's coherence rules; error detection latency +1 scan anyway —
the same latency cost as Option B's message hop, paid without the structural payoff.

### Option B — Centralize attribution in IS *(chosen)*

As described in Decision.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium (message reshaping; logic *moves* more than it changes) |
| Regression risk | Medium — feature-complete component; mitigated by test migration + S84 load suite + STB queue |
| Fixes incident | Yes |
| Fixes bug *class* | **Yes — by construction** (single registry, single gate, both attribution kinds) |
| Guard inventory | Guards 1–5 collapse into the one invariant + explicit Halted state |
| Maintainability | The cross-actor ordering contract ("`axisErrorMsg` must land before `UpdateThreadStatus` so the watcher fails before seeing the cleared thread") becomes IS-internal sequencing — a comment, not a protocol |

**Pros:** single source of truth; attribution becomes pure registry-vs-observations
logic, unit-testable in IS without I/O stubs; forensic logging centralizes where
the registry lives; retires implicit state.
**Cons:** `ScanObservations` is a fatter message (bounded: ≤8 axes, ≤8 threads, at
10 Hz — negligible); fault-decision latency gains one mailbox hop (safing I/O
itself already routes CS→CI fire-and-forget today, so the *action* path length is
unchanged); IS grows (it is already the state owner — growth lands where the state
lives); `ControllerStatusActorTest` attribution cases must migrate to
`InternalStateActorTest`.

### Option C — Shared registry (concurrent map) or actor merge — rejected

A shared `ConcurrentHashMap` trades message races for lock-ordering reasoning and
breaks the encapsulation that makes the rest of the HCD tractable; merging CS into
IS couples 10 Hz synchronous controller I/O to the highest-traffic mailbox in the
process. Neither survives contact with the constraints.

---

## Trade-off Analysis

The decisive trade is **fault-path latency vs. structural correctness**, and it is
smaller than it first appears. Option B adds one actor-mailbox hop (typically
sub-millisecond; worst-case bounded by IS mailbox depth) to the *decision* that
motors need safing. Against that: the controller's own error handlers provide the
hard-real-time layer; HCD-level `ST;MO` is defense in depth and already
fire-and-forget; and Option A *also* adds a full scan period (100 ms at action
rate) to error detection via its deferral — an order of magnitude more than the
hop it avoids. Option B is not slower than the realistic alternative.

The second trade is **churn in a feature-complete component vs. compounding guard
debt**. Five races in this seam across S55–S85 is the empirical trend line; each
patch narrowed a window without closing the class. Given the stated priority
(long-term stability over minimal diff) and that the attribution *logic* moves
largely intact — what changes is which map it reads — the churn is bounded and the
debt retirement is permanent.

## Consequences

**Easier:**
- Error and completion attribution share one gate; the incident class cannot recur.
- Attribution unit tests become pure (registry + `ScanObservations` in, state
  changes out) — no `stubIoWithThreadsAndAe` needed for logic cases; the missing
  regression coverage that let S85 slip through becomes cheap to write.
- The watcher-ordering contract and Halted-thread semantics become explicit,
  local, and documented in one actor.
- Future lifecycle features (further S84-style reuse refinements) touch one
  registry with enumerated states instead of two maps plus a protocol.

**Harder:**
- Bug archaeology for old logs: pre-/post-change log shapes differ (the forensic
  dump moves to IS). Mitigate by keeping field names (`axisThreads` →
  `threadRegistry`, `axesWithClearedThread`) recognizable.
- CS unit tests keep scan-mechanics coverage but lose attribution coverage —
  reviewers must not mistake the migration for deleted coverage.

**To revisit:**
- Whether `pendingControllerError`'s one-scan defer is still needed once real STB
  data confirms `_XQ` settle behavior under the new pipeline (keep until proven
  unnecessary on hardware).
- The `Await.result → pipeToSelf` refactor in CH (S70, deferred to PVT load
  testing) — unaffected by this ADR but adjacent; sequence after this lands.

## Action Items

1. [ ] Define `ScanObservations`; CS ships it once per scan (replaces `UpdateThreadStatus`).
2. [ ] Move `decideAxisAndControllerErrors` + `pendingControllerError` + `lastReportedAxisError` into IS; rewrite against `threadRegistry` under the attribution invariant.
3. [ ] Add explicit `Halted` state to registry entries; redirect `NotifyAxisHalted` → IS `ThreadHalted` (same synchronous ask in `checkAndInterrupt`); confirm S84 reuse path (`forceThread`, retained reservation) against the new state.
4. [ ] Reduce CS's `axisThreads` to advisory `threadsToQuery: Set[Int]` (feeds `_XQ` query + polling-rate policy only); IS forwards add/remove.
5. [ ] Eager TC fetch in CS on `errorCode != 0`; verify no race with the post-`#Init` TC consumer.
6. [ ] Route IS fault decision → existing `EnterFaulted` + `SendCommand("ST;MO")` via `commandActor`.
7. [ ] Tests: port attribution cases from `ControllerStatusActorTest` to `InternalStateActorTest` as pure-message tests; **add the S85 regression** (registry A→2, scan observing stale {2:B-active, 4:cleared} with `ae[A]=1`, expect NO error); keep CS tests for `_XQ` synthesis + scan mechanics; write the S84 reuse unit test (already queued in PROJECT_STATE §10) against the new `Halted` state.
8. [ ] Revalidate: full unit suite → sim-stack `stop_storm` (S84 baseline: 0 stop Errors) + `stop-idle` → add to STB hardware-verification queue.
9. [ ] On acceptance: update PROJECT_STATE §5 (design decisions) and the rationale comments in the three actors' headers; append S85 entry to SESSION_HISTORY.

## Rollback

Single-process, no persisted state or external protocol touched. Revert the commit
range; the S84 baseline behavior (including its known S85 defect) is restored.
Option A's deferral patch remains available as a stopgap if Option B must be backed
out under time pressure.

---

# Amendment A: observation freshness (S85 storm testing)

**Status:** Accepted (2026-07-14; same verification runs)
**Date:** 2026-07-14 (S85)
**Trigger:** `stop_storm` failures after the base ADR-001 implementation passed
unit tests: 5 misattributions across 4 HCDs, one reservation-exhaustion hard
failure, plus two latent defects surfaced by full-log analysis.

## Findings

**A1 — Stale-observation race (the gate was incomplete).** The base
implementation gated attribution on *which* threads a scan observed
(`observedThreads`), but not *when*. Under storm load, `ScanObservations`
delivery lagged its computation by up to ~1.4 s (IS mailbox pressure). In that
window a thread could complete, be attributed, be released, and be
**reallocated to a different axis** (or re-registered for the same axis via
S84 reuse) — and the stale scan, arriving after the new registration, would
both fail (`ae==1` entry flag misread) and complete the just-started command.
Judging an axis by an observation of its thread's *previous incarnation*.
Confirmed instances: HCD.1 23:11:21.416 (B) / .920 (C), HCD.2 23:11:33.357 (C)
/ 23:11:46.955 (D, reuse variant), HCD.3 23:11:23.531 (A) / 23:11:24.147 (C) /
23:11:39.366 (A).

**A2 — `#Init` reservation leak (pre-existing, S82-era).**
`sendAndWaitForThread` allocates via `ExecuteProgram` (which reserves the
thread in `unobservedThreads`) but never registers it with IS, so scan
attribution can never release it and no explicit release existed. The
reservation leaked for the session: HCD.4 ran the entire storm with thread 1
locked (`unobserved=[1,…]` two minutes after `#Init`), shrinking the motion
pool to 6. Also corrected: `#Init` is dynamically allocated — it does NOT run
on thread 0; only `#Setup` uses the literal thread 0.

**A3 — Reservation exhaustion is a CI-mailbox ordering race.** A scan
attributing several completions sends their `ReleaseThread`s, but an
`ExecuteProgram` already queued ahead of them in the CI mailbox sees every
thread reserved with the hardware fully free, and hard-fails
(HCD.4 23:11:42.973: `stopAxis F: No threads available`, `_NO=0x0`,
`unobserved=[1..7]`). "Clears within one QR scan" is true but useless to a
request already in flight.

**A4 — `clearActiveCommand` zeroes `activeThread`, diverging display state
from the registry.** A watcher timeout fires `clearActiveCommand`
(StateModel), zeroing `AxisCmdState.activeThread` while the program still
runs and the registry still holds the entry. `checkAndInterrupt` trusted the
display state: the post-timeout `stopAxis F` skipped both the HX and the
Halted mark, then allocated a fresh thread while F's real thread was still
registered and reserved — feeding A3.

## Decisions

**A1 → The invariant gains a freshness clause.** `ScanObservations` carries
`observedAt` (monotonic `System.nanoTime`, stamped by CS immediately before
the `_XQ` read); each registry entry carries `registeredAt` (stamped when IS
processes `RegisterThread`). An entry participates in attribution only if
`observedAt > registeredAt`. Soundness: `RegisterThread` is sent only after
the `XQ` succeeded, so `registeredAt` always postdates the program's actual
start — any scan that read `_XQ` before the program started is excluded
regardless of delivery delay. Re-registration (reuse) refreshes the fence.
`Halted` remains (it covers fresh observations of a deliberately-killed
program in the halt window).

With freshness carried per-observation, CS's advisory thread set no longer
serves attribution: **the scan queries `MG _XQ0..7` unconditionally**, and
`AddScanThread`/`RemoveScanThread`/`threadsToQuery`/`observedThreads` are all
deleted. The polling-rate policy's registered-threads term is fed by a new
edge-triggered `ThreadRegistryActivity(active)` signal from IS. Net effect:
CS-side bookkeeping latency can now only make observation *slower*, never
*wrong*. (The epoch-echo alternative was rejected for exactly this reason:
epoch freshness would ride the same CS mailbox that the storm showed lagging
by seconds.)

**A2 → `sendAndWaitForThread` releases its reservation** once the thread is
confirmed stopped (both the instant-completion and polled branches; NOT on
the timeout throw, where the reservation is correctly protective).

**A3 → One bounded retry** in `executeProgramAndWatch` when `ExecuteProgram`
fails with "No threads available" (200 ms; covers exactly the
in-flight-release race). A second failure reports as before.

**A4 → Interruption is registry-driven.** New IS query `GetAxisThread(axis)`
answers from the registry (non-Halted entries only); `checkAndInterrupt` uses
it instead of `AxisCmdState.activeThread`. `clearActiveCommand`'s display
semantics are unchanged.

## Consequences

- Delivery latency of scan results is now correctness-neutral end to end;
  under congestion the system degrades to *slow* attribution, never *wrong*
  attribution.
- One extra `MG` round-trip per scan at standby (all-8 `_XQ` query) — noise.
- Sub-scan completions attribute on the first scan whose read postdates
  registration — one action-rate scan worst case, same bound as before.
- Tests express staleness directly (capture `observedAt` before vs after
  registration) — the regression suite now encodes A1's replays verbatim.
- Revisit: the timeouts observed under storm (positionAxis 3 s floors, the
  HCD.2 stopAxis/E 5 s) are sim-load/latency effects, not attribution defects;
  reassess timeout margins under PVT load testing.

## Action items (all implemented with this amendment)

1. [x] `observedAt`/`registeredAt` freshness gate; all-8 `_XQ` query;
       Add/RemoveScanThread deleted; `ThreadRegistryActivity` signal.
2. [x] `#Init` reservation release + thread-0 doc corrections (initController,
       README).
3. [x] Allocation retry on transient exhaustion.
4. [x] `GetAxisThread` + registry-driven `checkAndInterrupt`.
5. [x] Regression tests: stale-reallocation, stale-reuse-window, activity
       signal edges, `GetAxisThread` divergence case.
6. [x] Re-run `stop_storm` across all 4 HCDs; expect 0 misattributions, 0
       allocation failures, no `unobserved=[1,…]` leak. — Verified 2026-07-14:
       20s and 40s storms, 384 + 736 submits, suspect=0; full-log audit showed
       zero misattributions (0 axis mismatches across 521 completions), the
       #Init thread re-allocated 101/83 times (leak closed), balanced registry
       entry/exit accounting, and quiescent end state on both sampled HCDs.

---

# Amendment B: the no-thread sentinel vs thread 0 (S86)

**Status:** Accepted (2026-07-15)
**Date:** 2026-07-15 (S86)
**Trigger:** Design review of the thread-0 last-resort policy while preparing
8-axis simulator testing (the S85 storms never lent thread 0 — the fleet has
≤7 axes — so this defect class was dormant and storm-invisible).

## Findings

`AxisCmdState.activeThread` used **0** as the "no thread" sentinel — in the
code, in the ICD (`CommandStateAxisX.activeThread: "0 if none"`), and in the
HMI. Thread 0 is a valid thread under the S85 last-resort policy, and the
collision produced four latent defects, none reachable until a controller has
8 configured axes:

- **B1 — un-interruptible thread-0 programs.** `checkAndInterrupt` collapsed
  `GetAxisThread`'s `Option[Int]` via `getOrElse(0)` and gated the halt on
  `activeThread > 0`: `Some(0)` and `None` were indistinguishable, so a
  program on thread 0 was never HX'd, never marked Halted, never reused — the
  follow-on would try to allocate a fresh thread against a fully-lent pool
  and hard-fail while the healthy program kept running.
- **B2 — the scan-confirmation gate vanished.** Every `CompletionMask` used
  `activeThread == 0` for "thread released"; a thread-0 command satisfied it
  from the watcher's initial snapshot. Worst case `stopAxis` (mask:
  released ∧ ¬moving) on a parked axis: instant completion with zero scan
  confirmation, violating the one-scan rule.
- **B3 — silent notification edges.** Register (0→0) and release (0→0) of a
  thread-0 command never fired `CmdStateChanged` for `activeThread`, so a
  watcher not rescued by another field change sat until its timeout.
- **B4 — HMI jog-reentrancy gate.** `engJog`'s "reentrant speed update"
  predicate (`Moving ∧ activeThread == 0`) would have allowed an engineering
  jog on top of a running thread-0 program.
- **B5 — `HaltExecution` itself skipped thread 0.** The CI actor's handler
  gated on `thread >= 1` and reported *success* without sending `HX 0`
  ("nothing to halt") — so even with B1 fixed, an interrupt of a thread-0
  program would falsely succeed, mark the still-running program Halted
  (leaking its registry entry and reservation: Halted entries have no scan
  exit), and the reuse `XQ ...,0` would be rejected busy. Found by an
  independent review pass of the B1-B4 fix; the pool-mock unit test could not
  catch it (the mock had no such special case), which is why the end-to-end
  8-axis test drives the REAL CI actor.

## Decision

**The no-thread sentinel becomes -1, end to end** (Angelic, S86). Internal
state, watcher masks, HMI server/page, and the published
`CommandStateAxisX.activeThread` all use -1 for "none"; 0 is exclusively a
real thread. `checkAndInterrupt` preserves the `Option` (`Some(0)` ≠ `None`)
rather than re-encoding it in an integer; `HaltExecution` halts any thread
`>= 0` (B5). The ICD description changes from
"0 if none" to "-1 if none" (Angelic to update the icd model files; the key
type is unchanged). `aps-ics-ui` does not read `activeThread` today; if it
gains a display, it must treat -1 as idle.

Rejected alternative: keep 0-if-none on the wire and decouple only the
internal decision paths (a `threadReleased` flag). Discarded because the
published value would then be *false* whenever thread 0 is lent — an
engineering UI showing "no thread" during a real motion program is the kind
of display fib that costs an hour of confusion per incident.

## Also in this change set

- The simulator now **rejects `XQ` on a busy thread with `?`** (real
  controller behavior). Without it, a regression of B1's follow-on path would
  silently "work" in simulation via completion-timer replacement and only
  fail on hardware.
- Default (`GalilHcdConfig.conf`) and simulator (`GalilHcdConfig-Simulator.conf`)
  configs go to **8 active axes**, making the thread-0 lend reachable in
  simulation.
- New `EightAxisThreadingTest` (pool-faithful CI mock honoring `forceThread` —
  closing the S84 open item — with real `selectThread` policy): full-pool
  allocation order, thread-0 lend-last, scan-gated thread-0 completion (B2/B3
  regressions), thread-0 interrupt-reuse (B1 regression). `HcdIntegrationTest`
  gains an end-to-end 8-concurrent-move test asserting the observed thread
  union is exactly {0..7}. `InternalStateActorTest` gains thread-0
  registration/attribution/freshness/`GetAxisThread` cases;
  `CommandWatcherActorTest` pins that `activeThread = 0` does NOT satisfy any
  completion mask.

## Consequences

- The wire value of an idle axis changes 0 → -1: any external consumer that
  compared `activeThread == 0` for idleness must update (ICD description
  change pending; no current consumer decides on it).
- Old-log archaeology: pre-S86 logs show `activeThread→0` for releases.
- The STB hardware-verification item (thread-0 interrupt-resume of automatic
  subroutines, §Amendment A/PROJECT_STATE §10) is UNCHANGED — this amendment
  makes the HCD side of thread-0 lending correct; the controller-side
  interrupt-resume behavior still needs empirical verification before
  8-motor reliance.

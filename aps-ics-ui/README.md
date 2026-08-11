# aps-ics-ui

The **engineering UI** for the APS Instrument Control System (ICS): a React /
[esw-ts](https://github.com/tmtsoftware/esw-ts) web application for commanding and
monitoring every assembly in `ics-assemblies` (APT, FO&C, PIT, PSH, STIM mechanisms,
including the tracking K-Mirror), plus read-only panels for the Galil motion HCDs.

For the selected component the UI shows push-based **liveness** (up / down), live
telemetry (a framed axis matrix, wheel slot / detent, K-Mirror mode + tracking state,
detector configuration), a **context-grouped** command panel with validity-gated
buttons, a read-only config view, and a running command/event log. HCD nodes show
registration, CPU load, the assemblies bound to them, and a link to the HCD's own
engineering HMI.

- **Stack:** React 19, Ant Design 5, TypeScript, Vite, `@tmtsoftware/esw-ts` v1.0.2.
- **Node:** 22 (use `nvm use 22`; a `conda base` env or a stale global node can shadow `nvm`).
- **Backend:** the `ics-assemblies` assemblies + Galil HCDs, reached through the CSW
  Location Service and ESW Gateway (see *Prerequisites*).
- **Path:** must live at a **space-free** path (`@web/test-runner` cannot resolve `%20`).

---

## Prerequisites

- **Node 22** and npm.
- A running backend the UI can resolve through esw-ts:
  - **CSW services** (`csw-services start -e`) — Location + Event (+ AAS for login).
  - **ESW Gateway** server — the UI submits commands and subscribes to events through it.
  - **GalilMotion HCD(s)** and the **ICS assemblies** (see the
    [ics-assemblies README](../ics-assemblies/)).

---

## Run (local development)

```bash
nvm use 22
npm install
npm start          # vite dev server
```

Then open [localhost:5173](http://localhost:5173). Log in via AAS, then pick a
component from the selector.

## Build / test / lint

```bash
npm run build      # production build (tsc + vite)
npm test           # unit tests
npm run fix        # eslint --fix + prettier --write
```

For a quick type check without emitting: `npx tsc --noEmit`.

---

## How it works

```
index.tsx → App.tsx
   ├── LocationServiceProvider          esw-ts LocationService (resolve + track)
   ├── ComponentLivenessProvider        push up/down for every assembly + HCD
   └── AuthContextProvider (AAS)
        └── Main                         the shell
             ├── ComponentSelector       tree of assemblies + HCDs, with liveness dots
             ├── AssemblyCpuBadge         header: assembly-container CPU (REQ-2-APS-0621)
             ├── (assembly selected)
             │    ├── <Assembly>Status    status band: state chips + framed axis matrix
             │    ├── <Assembly>Commands   context-grouped command panel
             │    ├── LifecycleCommands / ConfigTab / CommandEventLog
             └── (HCD selected)
                  └── HcdPanel            liveness + CPU + bound assemblies + HMI link
```

**Liveness (push, not poll).** `contexts/ComponentLivenessContext.tsx` opens one
`LocationService.track` subscription per registered assembly **and** per HCD, exposing
a prefix-keyed `up | down | unknown` map via `useComponentLiveness()`. This is the only
signal that a component is actually *registered*: the Event Service retains the last
value per key, so an event subscription replays a dead component's final telemetry as
if it were live. Liveness drives the selector dots, the header liveness tag,
stale-marking of a downed component's telemetry, command gating (an offline component's
buttons disable — no more 5-minute `queryFinal` timeouts), and an **on-demand**
`SupervisorLifecycleState` fetch that replaced a former 5 s admin poll (which also
re-resolved the location every tick and hid the down case).

**Descriptor-driven registry.** `components/registry.tsx` holds one descriptor per
assembly — its CSW prefix, HCD label, the `Commands` and `Status` components, and the
config view — keyed for `ComponentSelector`. Adding an assembly is a registry entry
plus its model + two components; `Main` needs no per-assembly changes.

**Status kit.** `components/statusLayout.tsx` composes every status panel from the same
pieces: `AssemblyStateStrip` (assembly / HCD / command state as chips), a **framed
`AxisMatrix`** (axes as *columns* — State / Position / Velocity / Indexed / In position
— plus `extraRows` for wheel slot, detent, and K-Mirror mode / slew / tracking), and a
muted `MetaFooter`. Detectors render a single unified **Detector configuration** table
(ROI, binning, gain, pixel encoding, readout rate, …) ordered per SDD Table 5-3, rather
than blocks named after the events (`setupStatus` / `configStatus` / `guidingStatus`)
that happen to carry each field.

**Command kit.** `components/commandKit.tsx` composes command panels: commands are
organised into context `CommandGroup`s (Setup / Motion / Recovery / …; detectors add
Cooling / Detector config / Exposure / …), with each interrupt kept beside the command
it interrupts (Stop in Motion, Abort in Recovery, Stop-loop with Start-loop).
Parameterised commands are `ParamCommand` cards — a labeled `Field` grid + their own
Submit; no-parameter commands are `ActionButton`s in an `Actions` cluster. Command
gating (`commandEnabled`, mirroring each assembly's validate gate) is unchanged.

**HCD panels.** `components/hcds.tsx` holds the Galil HCD descriptors and the
assembly→HCD bindings (derived from each model's `*_HCD_PREFIX_STR`, so the mapping
stays code-truth). `components/HcdPanel.tsx` shows an HCD's registration, process CPU
(its own `cpuLoad` event vs the 70% ceiling), the assemblies bound to it (clickable to
jump to them), and a **link** to the HCD's engineering HMI at `http://<host>:<9090+id>`
(host from the `cpuLoad` event's `hostname`), opened in a new tab and enabled only when
the HCD is up. The HMI is a direct, unauthenticated per-controller console that
bypasses the gateway and AAS, so the UI links to it rather than embedding it.

**Assembly CPU badge.** The header hosts `AssemblyCpuBadge`, a global readout that
subscribes to the assembly container's `APS.ICS.IcsAssemblies.cpuLoad` event and shows
the JVM's process CPU vs the REQ-2-APS-0621 70% ceiling (green / amber / red, with a
per-JVM tooltip).

---

## Structure

```
src/
├── App.tsx, index.tsx, index.css
├── config/AppConfig.js              application name (esw-ts metrics / deploy)
├── contexts/
│   ├── LocationServiceContext.tsx
│   └── ComponentLivenessContext.tsx  push up/down for assemblies + HCDs
├── hooks/                           useAuth, useQuery
├── models/
│   ├── stage.ts                     shared telemetry shapes, readers, command gating
│   ├── detector.ts                  shared detector telemetry shapes + gating
│   └── <assembly>.ts                one model per assembly (Setup builders + gating + config)
└── components/
    ├── Main.tsx                     shell: selector + assembly panels / HcdPanel + log
    ├── ComponentSelector.tsx        assembly + HCD picker (registry/hcds-driven, liveness dots)
    ├── registry.tsx                 assembly descriptors
    ├── hcds.tsx                     HCD descriptors + assembly→HCD bindings
    ├── HcdPanel.tsx                 HCD panel: liveness, CPU, bindings, HMI link
    ├── statusLayout.tsx             status kit: AssemblyStateStrip / AxisMatrix / MetaFooter
    ├── commandKit.tsx               command kit: CommandGroups / ParamCommand / Field / Actions
    ├── LivenessIndicator.tsx        liveness dot + tag
    ├── <Assembly>Commands.tsx       per-assembly command panels
    ├── <Assembly>Status.tsx         per-assembly status panels
    ├── detectorStatusBits.tsx       detector status blocks
    ├── ConfigTab.tsx, CommandEventLog.tsx, LifecycleCommands.tsx
    ├── AssemblyCpuBadge.tsx         header CPU-load badge (REQ-2-APS-0621)
    ├── statusBits.tsx, Login.tsx
```

---

## Adding an assembly to the UI

1. Add `models/<assembly>.ts` — prefix, HCD prefix (`*_HCD_PREFIX_STR`), Setup builders
   (mirror the ICD keys **exactly**), `commandEnabled`, and the config snapshot. Reuse
   `models/stage.ts` (or `models/detector.ts`) for shared shapes.
2. Add `components/<Assembly>Status.tsx` — compose with the **status kit**
   (`AssemblyStateStrip` + `AxisMatrix` (+ `extraRows`) + `MetaFooter`).
3. Add `components/<Assembly>Commands.tsx` — compose with the **command kit**
   (`CommandGroups` / `CommandGroup` / `ParamCommand` / `Field` / `Actions`), grouping
   commands by context and keeping each interrupt with its command.
4. In `components/registry.tsx`, import the model + components and add the descriptor;
   in `components/ComponentSelector.tsx`, make the assembly's node live (not a
   placeholder). Its HCD binding follows automatically from its `*_HCD_PREFIX_STR`
   (`hcds.tsx`).

A model parameter name has three independent consumer surfaces that must move together:
the Scala key accessor, the UI **wire** name (`choiceKey` / `floatKey` string), and the
UI **display** string. Keep all three in sync with the assembly's ICD keys.

---

## References

- esw-ts — [source](https://github.com/tmtsoftware/esw-ts) ·
  [docs](https://tmtsoftware.github.io/esw-ts/)
- [ics-assemblies](../ics-assemblies/) — the assemblies this UI commands
- [CSW documentation](https://tmtsoftware.github.io/csw/6.0.0/)

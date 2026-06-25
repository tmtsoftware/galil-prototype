# aps-ics-ui

The **engineering UI** for the APS Instrument Control System (ICS): a React /
[esw-ts](https://github.com/tmtsoftware/esw-ts) web application for commanding and
monitoring every assembly in `ics-assemblies` (APT, FO&C, PIT, PSH, STIM mechanisms,
including the tracking K-Mirror).

A descriptor-driven registry maps each assembly to its own command and status panels.
For the selected assembly the UI shows live telemetry (operational / command state, axis
position, wheel slot, K-Mirror mode and tracking state), a per-assembly command panel with
inline parameters and validity-gated buttons, a read-only config view, and a running
command/event log.

- **Stack:** React 19, Ant Design 5, TypeScript, Vite, `@tmtsoftware/esw-ts` v1.0.2.
- **Node:** 22 (use `nvm use 22`; a `conda base` env can shadow `nvm`).
- **Backend:** the `ics-assemblies` assemblies, reached through the CSW Location Service
  and ESW Gateway (see *Prerequisites*).

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

Then open [localhost:5173](http://localhost:5173). Log in via AAS, then pick an assembly
from the selector.

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
   └── LocationServiceContext        resolve the gateway / assemblies via esw-ts
        └── Login (useAuth, AAS)
             └── Main                 the shell
                  ├── ComponentSelector   choose an assembly (registry-driven)
                  ├── LifecycleCommands    shutdown / restart / lifecycle
                  ├── <Mechanism>Commands  per-assembly command panel
                  ├── <Mechanism>Status    per-assembly live telemetry
                  ├── ConfigTab            read-only config view
                  └── CommandEventLog      submitted commands + responses + events
```

**Descriptor-driven registry.** `components/registry.tsx` holds one descriptor per
assembly — its CSW prefix, HCD label, the `Commands` and `Status` components, and the
config view — keyed for `ComponentSelector`. Adding an assembly is a registry entry plus
its model + two components; `Main` needs no per-assembly changes.

**Per-assembly model + components.** Each assembly has:
- `models/<assembly>.ts` — CSW prefix, Setup builders (paramSet shapes mirroring the
  assembly's ICD keys **exactly**), command-gating, and the config snapshot. Common
  telemetry shapes / readers / gating live in the shared `models/stage.ts`.
- `components/<Assembly>Commands.tsx` — one row per command, inline parameters,
  buttons gated by `commandEnabled` (mirrors the assembly's validate gate).
- `components/<Assembly>Status.tsx` — live `status` + `axisStatus` (and, for the
  K-Mirror, `mode` / `slewModeState` / `trackingModeState`).

---

## Structure

```
src/
├── App.tsx, index.tsx, index.css
├── config/AppConfig.js              application name (esw-ts metrics / deploy)
├── contexts/LocationServiceContext.tsx
├── hooks/                           useAuth, useQuery
├── models/
│   ├── stage.ts                     shared telemetry shapes, readers, command gating
│   └── <assembly>.ts                one model per assembly (Setup builders + gating + config)
└── components/
    ├── Main.tsx                     shell: selector + panels + log
    ├── ComponentSelector.tsx        assembly picker (registry-driven)
    ├── registry.tsx                 assembly descriptors
    ├── <Assembly>Commands.tsx       per-assembly command panels
    ├── <Assembly>Status.tsx         per-assembly status panels
    ├── ConfigTab.tsx, CommandEventLog.tsx, LifecycleCommands.tsx
    ├── statusBits.tsx, Login.tsx
```

---

## Adding an assembly to the UI

1. Add `models/<assembly>.ts` — prefix, Setup builders (mirror the ICD keys exactly),
   `commandEnabled`, and the config snapshot. Reuse `models/stage.ts` for shared shapes.
2. Add `components/<Assembly>Commands.tsx` and `components/<Assembly>Status.tsx`.
3. In `components/registry.tsx`: import the model + components and add the descriptor.
4. In `components/ComponentSelector.tsx`: make the assembly's entry live (not a placeholder).

A model parameter name has three independent consumer surfaces that must move together:
the Scala key accessor, the UI **wire** name (`choiceKey` / `floatKey` string), and the UI
**display** string. Keep all three in sync with the assembly's ICD keys.

---

## References

- esw-ts — [source](https://github.com/tmtsoftware/esw-ts) ·
  [docs](https://tmtsoftware.github.io/esw-ts/)
- [ics-assemblies](../ics-assemblies/) — the assemblies this UI commands
- [CSW documentation](https://tmtsoftware.github.io/csw/6.0.0/)
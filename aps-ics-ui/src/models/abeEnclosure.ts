/*
 * ABE Enclosure model (APS.ICS.ABE.Enclosure): constants, Setup builders,
 * command gating and the config snapshot. The assembly is a self-contained
 * MOCK: the ICD's send-model actuates the purge / coolant valves through the
 * GalilMotion HCD's digital outputs (setBit), but the output-bit map is not
 * defined yet, so the mock holds valve state internally (fixed ~0.5 s valve
 * actuation) and derives its published flow/pressure telemetry from it; the
 * environment readings are plausible static values. hcdState is READY by fiat.
 * Key names and choice domains mirror AbeEnclosureKeys EXACTLY.
 */
import { ComponentId, Prefix, Setup, choiceKey } from '@tmtsoftware/esw-ts'
import type { ConfigSection, StatusSnapshot } from './stage'

export { readStatus } from './stage'
export type { StatusSnapshot, ConfigRow, ConfigSection } from './stage'

export const ABEEN_PREFIX_STR = 'APS.ICS.ABE.Enclosure'
export const ABEEN_PREFIX = Prefix.fromString(ABEEN_PREFIX_STR)
export const ABEEN_COMPONENT_ID = new ComponentId(ABEEN_PREFIX, 'Assembly')

export const STATUS_EVENT = 'status'

// Config Service path (prefix-mirrored). NOT seeded — the mock has no config
// resource, so the Configuration tab always falls back to the snapshot below.
export const ABEEN_CONFIG_PATH = 'APS/ICS/ABE/Enclosure.conf'

// Choice domain — must match ChoiceKey.make("action", ...) in AbeEnclosureKeys
// (same domain on both commands).
export const VALVE_ACTIONS = ['ON', 'OFF'] as const
export type ValveAction = (typeof VALVE_ACTIONS)[number]

// ---- Setup builders ---------------------------------------------------------
export const commandPurgeAirCmd = (action: ValveAction): Setup =>
  new Setup(ABEEN_PREFIX, 'commandPurgeAir', [choiceKey('action', VALVE_ACTIONS).set(action)])

export const commandCoolantControlValveCmd = (action: ValveAction): Setup =>
  new Setup(ABEEN_PREFIX, 'commandCoolantControlValve', [choiceKey('action', VALVE_ACTIONS).set(action)])

// ---- Command gating: mirrors AbeEnclosureHandlers.validateCommand -----------
// One command at a time (PROCESSING rejects); FAULTED rejects (unreachable in
// the mock, kept for symmetry with the modeled assembly states).
export const commandEnabled = (s: StatusSnapshot, ready: boolean, busy: boolean): boolean =>
  ready && !busy && s.commandState !== 'PROCESSING' && s.assemblyState !== 'FAULTED'

// ---- Read-only config snapshot ----------------------------------------------
export const ABEEN_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'ABE Enclosure — MOCK (self-contained)',
    rows: [
      { label: 'Actuation', value: 'internal valve state only — no HCD bound' },
      { label: 'Real path (ICD send-model)', value: 'GalilMotion HCD setBit (output map TBD)' },
      { label: 'Mock valve actuation time', value: '0.5 s' },
      { label: 'Purge air flow (valve ON)', value: '30.0 l/s' },
      { label: 'Coolant (valve ON)', value: '2.0 bar; 0.5 l/s per detector branch' },
      { label: 'Environment telemetry', value: 'static plausible values (~20 degC, 5% RH)' }
    ]
  }
]

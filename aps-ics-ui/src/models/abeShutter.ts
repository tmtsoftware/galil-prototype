/*
 * ABE Shutter model (APS.ICS.ABE.Shutter): constants, Setup builder, command
 * gating and the config snapshot. The assembly is a self-contained MOCK: the
 * ICD's send-model actuates the shutter through the GalilMotion HCD's digital
 * outputs (setBit), but the output-bit map is not defined yet, so the mock
 * holds blade state internally (blades start CLOSED, fixed ~1.5 s travel) and
 * binds no HCD — its published hcdState is READY by fiat. Key names and choice
 * domains mirror AbeShutterKeys EXACTLY.
 */
import { ComponentId, Prefix, Setup, choiceKey } from '@tmtsoftware/esw-ts'
import type { ConfigSection, StatusSnapshot } from './stage'

export { readStatus } from './stage'
export type { StatusSnapshot, ConfigRow, ConfigSection } from './stage'

export const ABESH_PREFIX_STR = 'APS.ICS.ABE.Shutter'
export const ABESH_PREFIX = Prefix.fromString(ABESH_PREFIX_STR)
export const ABESH_COMPONENT_ID = new ComponentId(ABESH_PREFIX, 'Assembly')

export const STATUS_EVENT = 'status'

// Config Service path (prefix-mirrored). NOT seeded — the mock has no config
// resource, so the Configuration tab always falls back to the snapshot below.
export const ABESH_CONFIG_PATH = 'APS/ICS/ABE/Shutter.conf'

// Choice domain — must match ChoiceKey.make("command", ...) in AbeShutterKeys.
export const SHUTTER_COMMANDS = ['OPEN', 'CLOSE'] as const
export type ShutterCommand = (typeof SHUTTER_COMMANDS)[number]

// ---- Setup builder ----------------------------------------------------------
export const commandShutterCmd = (command: ShutterCommand): Setup =>
  new Setup(ABESH_PREFIX, 'commandShutter', [choiceKey('command', SHUTTER_COMMANDS).set(command)])

// ---- Command gating: mirrors AbeShutterHandlers.validateCommand -------------
// One command at a time (PROCESSING rejects); FAULTED rejects (unreachable in
// the mock, kept for symmetry with the modeled assembly states).
export const commandEnabled = (s: StatusSnapshot, ready: boolean, busy: boolean): boolean =>
  ready && !busy && s.commandState !== 'PROCESSING' && s.assemblyState !== 'FAULTED'

// ---- Read-only config snapshot ----------------------------------------------
export const ABESH_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'ABE Shutter — MOCK (self-contained)',
    rows: [
      { label: 'Actuation', value: 'internal state only — no HCD bound' },
      { label: 'Real path (ICD send-model)', value: 'GalilMotion HCD setBit (output map TBD)' },
      { label: 'Blades', value: 'A + B move together; start CLOSED' },
      { label: 'Mock travel time', value: '1.5 s' }
    ]
  }
]

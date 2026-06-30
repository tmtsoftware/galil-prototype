/*
 * InsertionStage model: constants, Setup builders, command gating and the
 * config snapshot for the APS.ICS.STIM.InsertionStage assembly.
 *
 * Shared stage telemetry (StatusSnapshot / AxisSnapshot / readStatus / readAxis)
 * and the command-gating primitive live in ./stage and are re-exported here, so
 * existing importers of this module are unaffected. Key names and choice domains
 * mirror ics-assemblies InsertionStageKeys.scala EXACTLY.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

// Re-export the shared telemetry shapes/readers under this module's surface so
// Main and the InsertionStage panels keep importing them from here.
export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const IS_PREFIX_STR = 'APS.ICS.STIM.InsertionStage'
export const IS_PREFIX = Prefix.fromString(IS_PREFIX_STR)
export const IS_COMPONENT_ID = new ComponentId(IS_PREFIX, 'Assembly')

// Event names published by InsertionStageHandlers.publishTelemetry
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const IS_CONFIG_PATH = 'APS/ICS/STIM/InsertionStage.conf'

// Choice domains — must match ChoiceKey.make(...) in InsertionStageKeys.scala
export const LIGHT_SOURCES = ['SKY', 'STIMULUS'] as const
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type LightSource = (typeof LIGHT_SOURCES)[number]
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(IS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(IS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(IS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(IS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(IS_PREFIX, 'abortErrorRecovery')

export const selectSourceCmd = (src: LightSource): Setup =>
  new Setup(IS_PREFIX, 'selectSource', [choiceKey('lightSource', LIGHT_SOURCES).set(src)])

export const positionStageCmd = (method: PositionMethod, mm: number): Setup =>
  new Setup(IS_PREFIX, 'positionStage', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('value', Units.millimeter).set([mm])
  ])

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'selectSource'
  | 'positionStage'
  | 'stop'
  | 'abortErrorRecovery'

const kindOf = (cmd: CmdName): CmdKind =>
  cmd === 'configure' || cmd === 'home'
    ? 'configHome'
    : cmd === 'abortErrorRecovery'
      ? 'abort'
      : cmd === 'stop'
        ? 'stop'
        : 'motion'

export const commandEnabled = (
  cmd: CmdName,
  s: StatusSnapshot,
  ready: boolean,
  busy: boolean
): boolean => gateByKind(kindOf(cmd), s, ready, busy)

// ===========================================================================
// Selector/HCD label and the read-only config snapshot.
// ===========================================================================

// The HCD this assembly is bound to (from the container ComponentInfo
// connection; controller 2 / shared FO&C per SDD Fig 2-2). Static label only.
export const IS_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.2'
export const IS_HCD_LABEL = 'Galil HCD 2'

// Static mirror of ics-assemblies InsertionStage.conf, shown when the Config
// Service has no seeded active version. Simulator bring-up values, NOT calibrated.
export const IS_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "stage" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'In-position threshold', value: '0.01 mm' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 6-1)',
    rows: [
      { label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.2' },
      { label: 'Galil channel', value: 'A (provisional)' }
    ]
  },
  {
    title: 'Motion (SDD Table 6-1)',
    rows: [
      { label: 'Velocity', value: '20.0 mm/sec' },
      { label: 'Acceleration', value: '100.0 mm/sec²' },
      { label: 'Deceleration', value: '100.0 mm/sec²' },
      { label: 'Index offset', value: '100.0 mm' },
      { label: 'Index speed', value: '2.0 mm/sec' }
    ]
  },
  {
    title: 'Source positions (SDD Table 6-24)',
    rows: [
      { label: 'Stimulus position', value: '60.0 mm' },
      { label: 'Sky position', value: '-60.0 mm' }
    ]
  }
]

/*
 * PitFocusStage model: constants, Setup builders, command gating and the config
 * snapshot for the APS.ICS.PIT.FocusStage assembly.
 *
 * Built on the same base StageAssemblyHandlers as the InsertionStage (a single
 * linear "stage" axis), so it shares the telemetry shapes / readers / gating in
 * ./stage. The only assembly-specific command is positionFocusStage;
 * configure/home/moveToDefaultPosition/stop/abortErrorRecovery are the common
 * base commands. Key names and choice domains mirror ics-assemblies
 * PitFocusStageKeys.scala EXACTLY.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const PITFS_PREFIX_STR = 'APS.ICS.PIT.FocusStage'
export const PITFS_PREFIX = Prefix.fromString(PITFS_PREFIX_STR)
export const PITFS_COMPONENT_ID = new ComponentId(PITFS_PREFIX, 'Assembly')

// Event names published by PitFocusStageHandlers.publishTelemetry: status + a
// single axisStatus.
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const PITFS_CONFIG_PATH = 'APS/ICS/PIT/FocusStage.conf'

// Choice domain — must match ChoiceKey.make(...) in the assembly keys.
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(PITFS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(PITFS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(PITFS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(PITFS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(PITFS_PREFIX, 'abortErrorRecovery')

// positionFocusStage: method + value (single axis). Key name is `value` (mirrors
// PositionFocusStageCommand.valueKey).
export const positionFocusStageCmd = (method: PositionMethod, mm: number): Setup =>
  new Setup(PITFS_PREFIX, 'positionFocusStage', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('value', Units.millimeter).set([mm])
  ])

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'positionFocusStage'
  | 'stop'
  | 'abortErrorRecovery'

const kindOf = (cmd: CmdName): CmdKind =>
  cmd === 'configure' || cmd === 'home'
    ? 'configHome'
    : cmd === 'abortErrorRecovery'
      ? 'abort'
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

// Bound HCD (container ComponentInfo connection): controller 1, channel D
// (SDD Fig 2-2). Static label.
export const PITFS_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.1'
export const PITFS_HCD_LABEL = 'Galil HCD 1'

// Static mirror of ics-assemblies PitFocusStage.conf, shown when the Config
// Service has no seeded active version. Simulator values, NOT calibrated.
export const PITFS_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "stage" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'In-position threshold', value: '0.01 mm' },
      { label: 'Galil channel', value: 'D (provisional)' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 6-1)',
    rows: [{ label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.1' }]
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
  }
]

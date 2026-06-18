/*
 * SteeringBeamSplitterStage model: constants, Setup builders, command gating and
 * the config snapshot for the APS.ICS.FOC.SteeringBeamSplitterStage assembly.
 *
 * Built on the same base StageAssemblyHandlers as the InsertionStage, so it
 * shares the telemetry shapes / readers / gating in ./stage. The only command
 * that differs is positionBeamSplitter (x,y); configure/home/moveToDefaultPosition
 * /stop/abortErrorRecovery are the common base commands. Key names and choice
 * domains mirror ics-assemblies SteeringBeamSplitterStageKeys.scala EXACTLY.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const SBS_PREFIX_STR = 'APS.ICS.FOC.SteeringBeamSplitterStage'
export const SBS_PREFIX = Prefix.fromString(SBS_PREFIX_STR)
export const SBS_COMPONENT_ID = new ComponentId(SBS_PREFIX, 'Assembly')

// Event names published by SteeringBeamSplitterStageHandlers.publishTelemetry:
// one status event + a SEPARATE axis-status event per axis (x and y).
export const STATUS_EVENT = 'status'
export const X_AXIS_EVENT = 'xAxisStatus'
export const Y_AXIS_EVENT = 'yAxisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const SBS_CONFIG_PATH = 'APS/ICS/FOC/SteeringBeamSplitterStage.conf'

// Choice domain — must match ChoiceKey.make(...) in the assembly keys.
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(SBS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(SBS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(SBS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(SBS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(SBS_PREFIX, 'abortErrorRecovery')

// positionBeamSplitter steers the pupil in (x,y): one method + x + y, sent
// together (the assembly drives both axes from the single command).
export const positionBeamSplitterCmd = (
  method: PositionMethod,
  xMm: number,
  yMm: number
): Setup =>
  new Setup(SBS_PREFIX, 'positionBeamSplitter', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('xValue', Units.millimeter).set([xMm]),
    floatKey('yValue', Units.millimeter).set([yMm])
  ])

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'positionBeamSplitter'
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

// Bound HCD (container ComponentInfo connection): controller 2, channels B/C —
// the same FO&C controller the Insertion Stage uses (SDD Fig 2-2). Static label.
export const SBS_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.2'
export const SBS_HCD_LABEL = 'Galil HCD 2'

// Static mirror of ics-assemblies SteeringBeamSplitterStage.conf, shown when the
// Config Service has no seeded active version. Simulator values, NOT calibrated.
export const SBS_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "xStage" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'In-position threshold', value: '0.01 mm' },
      { label: 'Galil channel', value: 'B (provisional)' }
    ]
  },
  {
    title: 'Axis "yStage" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'In-position threshold', value: '0.01 mm' },
      { label: 'Galil channel', value: 'C (provisional)' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 6-1)',
    rows: [{ label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.2' }]
  },
  {
    title: 'Motion — both axes (SDD Table 6-1)',
    rows: [
      { label: 'Velocity', value: '20.0 mm/sec' },
      { label: 'Acceleration', value: '100.0 mm/sec²' },
      { label: 'Deceleration', value: '100.0 mm/sec²' },
      { label: 'Index offset', value: '100.0 mm' },
      { label: 'Index speed', value: '2.0 mm/sec' }
    ]
  }
]

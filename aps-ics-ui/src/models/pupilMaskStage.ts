/*
 * PupilMaskStage model: constants, Setup builders, command gating and the config
 * snapshot for the APS.ICS.STIM.PupilMaskStage assembly.
 *
 * Built on the same base StageAssemblyHandlers as the other stage assemblies
 * (X/Y linear + Phi ROTATIONAL), so it shares the telemetry shapes / readers /
 * gating in ./stage. The only assembly-specific command is positionMaskStage;
 * configure/home/moveToDefaultPosition/stop/abortErrorRecovery are the common
 * base commands. Key names and choice domains mirror ics-assemblies
 * StimPupilMaskStageKeys.scala EXACTLY.
 *
 * Phi is the rotational axis (about the optical/z axis); its demand and telemetry
 * are in degrees. (The phiAxisStatus position key is degree; the positionValuePhi
 * command value is read unit-agnostically by the assembly.)
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const PMS_PREFIX_STR = 'APS.ICS.STIM.PupilMaskStage'
export const PMS_PREFIX = Prefix.fromString(PMS_PREFIX_STR)
export const PMS_COMPONENT_ID = new ComponentId(PMS_PREFIX, 'Assembly')

// Event names published by StimPupilMaskStageHandlers.publishTelemetry: one
// status event + a SEPARATE axis-status event per axis (x/y/phi).
export const STATUS_EVENT = 'status'
export const X_AXIS_EVENT = 'xAxisStatus'
export const Y_AXIS_EVENT = 'yAxisStatus'
export const PHI_AXIS_EVENT = 'phiAxisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const PMS_CONFIG_PATH = 'APS/ICS/STIM/PupilMaskStage.conf'

// Choice domain — must match ChoiceKey.make(...) in the assembly keys.
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(PMS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(PMS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(PMS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(PMS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(PMS_PREFIX, 'abortErrorRecovery')

// positionMaskStage: method + x (mm) + y (mm) + phi (deg), sent together (the
// assembly drives all three axes from the single command).
export const positionMaskStageCmd = (
  method: PositionMethod,
  xMm: number,
  yMm: number,
  phiDeg: number
): Setup =>
  new Setup(PMS_PREFIX, 'positionMaskStage', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('positionValueX', Units.millimeter).set([xMm]),
    floatKey('positionValueY', Units.millimeter).set([yMm]),
    floatKey('positionValuePhi', Units.degree).set([phiDeg])
  ])

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'positionMaskStage'
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

// Bound HCD (container ComponentInfo connection): controller 4, channels D/E/F —
// the Fiber Source + Pupil Mask controller (SDD Fig 2-2). Static label.
export const PMS_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.4'
export const PMS_HCD_LABEL = 'Galil HCD 4'

// Static mirror of ics-assemblies PupilMaskStage.conf, shown when the Config
// Service has no seeded active version. Simulator values, NOT calibrated.
export const PMS_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "xAxis" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'Galil channel', value: 'D (provisional)' }
    ]
  },
  {
    title: 'Axis "yAxis" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'Galil channel', value: 'E (provisional)' }
    ]
  },
  {
    title: 'Axis "phiAxis" — rotational (SDD Table 6-1)',
    rows: [
      { label: 'Counts per degree', value: '1000.0 counts/deg' },
      { label: 'Is rotational', value: 'true' },
      { label: 'Positioning method', value: 'shortest' },
      { label: 'Default position', value: '0.0 deg' },
      { label: 'In-position threshold', value: '0.1 deg' },
      { label: 'Galil channel', value: 'F (provisional)' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 6-1)',
    rows: [{ label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.4' }]
  },
  {
    title: 'Motion — linear axes X/Y (SDD Table 6-1)',
    rows: [
      { label: 'Velocity', value: '20.0 mm/sec' },
      { label: 'Acceleration', value: '100.0 mm/sec²' },
      { label: 'Deceleration', value: '100.0 mm/sec²' },
      { label: 'Index offset', value: '100.0 mm' },
      { label: 'Index speed', value: '2.0 mm/sec' }
    ]
  },
  {
    title: 'Motion — rotational axis Phi (SDD Table 6-1)',
    rows: [
      { label: 'Velocity', value: '360.0 deg/sec' },
      { label: 'Acceleration', value: '720.0 deg/sec²' },
      { label: 'Deceleration', value: '720.0 deg/sec²' },
      { label: 'Index offset', value: '0.0 deg' },
      { label: 'Index speed', value: '36.0 deg/sec' }
    ]
  }
]

/*
 * TiltPlate model: constants, Setup builders, command gating and the config
 * snapshot for the APS.ICS.FOC.TiltPlate assembly.
 *
 * Built on the same base StageAssemblyHandlers as the other stage assemblies (two
 * linear axes, X and Y), so it shares the telemetry shapes / readers / gating in
 * ./stage. The only assembly-specific command is positionTiltPlate;
 * configure/home/moveToDefaultPosition/stop/abortErrorRecovery are the common
 * base commands. Key names and choice domains mirror ics-assemblies
 * FocTiltPlateKeys.scala EXACTLY.
 *
 * NOTE: positionTiltPlate commands a PUPIL-plane (M1) translation in mm; the
 * assembly converts each demand to a stage move via the per-axis stage->M1
 * factor (stage_mm = pupil_mm / factor). The xAxisStatus/yAxisStatus telemetry
 * reports STAGE position, not pupil position.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const TP_PREFIX_STR = 'APS.ICS.FOC.TiltPlate'
export const TP_PREFIX = Prefix.fromString(TP_PREFIX_STR)
export const TP_COMPONENT_ID = new ComponentId(TP_PREFIX, 'Assembly')

// Event names published by FocTiltPlateHandlers.publishTelemetry: one status
// event + a SEPARATE axis-status event per axis (x and y).
export const STATUS_EVENT = 'status'
export const X_AXIS_EVENT = 'xAxisStatus'
export const Y_AXIS_EVENT = 'yAxisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const TP_CONFIG_PATH = 'APS/ICS/FOC/TiltPlate.conf'

// Choice domain — must match ChoiceKey.make(...) in the assembly keys.
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(TP_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(TP_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(TP_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(TP_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(TP_PREFIX, 'abortErrorRecovery')

// positionTiltPlate translates the pupil in (x,y): one method + x + y, sent
// together (the assembly converts pupil mm -> stage mm and drives both axes).
export const positionTiltPlateCmd = (
  method: PositionMethod,
  xMm: number,
  yMm: number
): Setup =>
  new Setup(TP_PREFIX, 'positionTiltPlate', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('xValue', Units.millimeter).set([xMm]),
    floatKey('yValue', Units.millimeter).set([yMm])
  ])

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'positionTiltPlate'
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

// Bound HCD (container ComponentInfo connection): controller 3, channels B/C —
// the FO&C K-Mirror/Tilt controller (SDD Fig 2-2). Static label.
export const TP_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.3'
export const TP_HCD_LABEL = 'Galil HCD 3'

// Static mirror of ics-assemblies TiltPlate.conf, shown when the Config Service
// has no seeded active version. Simulator values, NOT calibrated.
export const TP_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "xAxis" — linear (SDD Table 6-1)',
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
    title: 'Axis "yAxis" — linear (SDD Table 6-1)',
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
    rows: [{ label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.3' }]
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
  },
  {
    title: 'Stage → M1 factors (SDD Table 6-15) — NOT calibrated, direction TBC',
    rows: [
      { label: 'X stage → M1 factor', value: '0.5 m1 mm per stage mm' },
      { label: 'Y stage → M1 factor', value: '0.5 m1 mm per stage mm' }
    ]
  }
]

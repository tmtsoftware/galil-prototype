/*
 * CollimatorUnit model: constants, Setup builders, command gating and the config
 * snapshot for the APS.ICS.FOC.CollimatorUnit assembly.
 *
 * Built on the same base StageAssemblyHandlers as the other stage assemblies, so
 * it shares the telemetry shapes / readers / gating in ./stage. The
 * assembly-specific commands are changeScale, positionFrontAxis and
 * positionRearAxis; configure/home/moveToDefaultPosition/stop/abortErrorRecovery
 * are the common base commands. Key names and choice domains mirror
 * ics-assemblies CollimatorUnitKeys.scala EXACTLY.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const CU_PREFIX_STR = 'APS.ICS.FOC.CollimatorUnit'
export const CU_PREFIX = Prefix.fromString(CU_PREFIX_STR)
export const CU_COMPONENT_ID = new ComponentId(CU_PREFIX, 'Assembly')

// Event names published by CollimatorUnitHandlers.publishTelemetry: one status
// event + a SEPARATE axis-status event per axis (front and rear).
export const STATUS_EVENT = 'status'
export const FRONT_AXIS_EVENT = 'frontAxisStatus'
export const REAR_AXIS_EVENT = 'rearAxisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const CU_CONFIG_PATH = 'APS/ICS/FOC/CollimatorUnit.conf'

// Choice domain — must match ChoiceKey.make(...) in the assembly keys.
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(CU_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(CU_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(CU_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(CU_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(CU_PREFIX, 'abortErrorRecovery')

// changeScale: a single percent that the assembly maps to a per-axis move via
// each axis's scale constant.
export const changeScaleCmd = (percentChange: number): Setup =>
  new Setup(CU_PREFIX, 'changeScale', [floatKey('percentChange').set([percentChange])])

// positionFrontAxis / positionRearAxis: method + value, one axis each.
export const positionFrontAxisCmd = (method: PositionMethod, mm: number): Setup =>
  new Setup(CU_PREFIX, 'positionFrontAxis', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('positionValue', Units.millimeter).set([mm])
  ])

export const positionRearAxisCmd = (method: PositionMethod, mm: number): Setup =>
  new Setup(CU_PREFIX, 'positionRearAxis', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('positionValue', Units.millimeter).set([mm])
  ])

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'changeScale'
  | 'positionFrontAxis'
  | 'positionRearAxis'
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

// Bound HCD (container ComponentInfo connection): controller 2, channels D/E —
// the same FO&C controller the other FO&C stages use (SDD Fig 2-2). Static label.
export const CU_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.2'
export const CU_HCD_LABEL = 'Galil HCD 2'

// Static mirror of ics-assemblies CollimatorUnit.conf, shown when the Config
// Service has no seeded active version. Simulator values, NOT calibrated.
export const CU_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "frontAxis" — linear (SDD Table 6-1)',
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
    title: 'Axis "rearAxis" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'In-position threshold', value: '0.01 mm' },
      { label: 'Galil channel', value: 'E (provisional)' }
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
  },
  {
    title: 'Scale constants (SDD Table 6-12) — NOT calibrated',
    rows: [
      { label: 'Front axis scale constant', value: '0.5 mm per 1% scale' },
      { label: 'Rear axis scale constant', value: '-0.5 mm per 1% scale' }
    ]
  }
]

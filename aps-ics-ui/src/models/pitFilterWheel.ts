/*
 * PitFilterWheel model: constants, Setup builders, command gating and the config
 * snapshot for the APS.ICS.PIT.FilterWheel assembly (SDD §7 Wheel Assemblies).
 *
 * Built on the common WheelAssemblyHandlers (a single ROTATING "filterWheel"
 * axis), so it shares the telemetry shapes / readers / gating in ./stage. The
 * wheel-common commands are positionWheel (numbered slot 1-7) and positionMotor
 * (low-level engineering move); the PIT-specific command is selectFilter.
 * configure / home / moveToDefaultPosition / stop / abortErrorRecovery are the
 * common base commands. Key names and choice domains mirror ics-assemblies
 * PitFilterWheelKeys.scala EXACTLY — a mismatched name or key TYPE yields
 * Invalid(MissingKey) at the assembly.
 */
import {
  ComponentId,
  Prefix,
  Setup,
  choiceKey,
  floatKey,
  intKey,
  Units
} from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type {
  StatusSnapshot,
  AxisSnapshot,
  ConfigRow,
  ConfigSection
} from './stage'

export const PITFW_PREFIX_STR = 'APS.ICS.PIT.FilterWheel'
export const PITFW_PREFIX = Prefix.fromString(PITFW_PREFIX_STR)
export const PITFW_COMPONENT_ID = new ComponentId(PITFW_PREFIX, 'Assembly')

// Event names published by PitFilterWheelHandlers.publishTelemetry: status + a
// single axisStatus.
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const PITFW_CONFIG_PATH = 'APS/ICS/PIT/FilterWheel.conf'

// ---- Choice domains — must match ChoiceKey.make(...) in the assembly keys -----
export const FILTERS = [
  'F890N',
  'F891N',
  'F850M',
  'F750W',
  'F810N',
  'F630N',
  'F865N'
] as const
export type Filter = (typeof FILTERS)[number]

export const WHEEL_POSITIONS = ['1', '2', '3', '4', '5', '6', '7'] as const
export type WheelPosition = (typeof WHEEL_POSITIONS)[number]

export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

export const POSITION_TARGETS = ['WHEEL', 'MOTOR'] as const
export type PositionTarget = (typeof POSITION_TARGETS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) -----
export const homeCmd = (): Setup => new Setup(PITFW_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(PITFW_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup =>
  new Setup(PITFW_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(PITFW_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup =>
  new Setup(PITFW_PREFIX, 'abortErrorRecovery')

// selectFilter(filter): resolve a filter name to its assigned wheel slot.
export const selectFilterCmd = (filter: Filter): Setup =>
  new Setup(PITFW_PREFIX, 'selectFilter', [
    choiceKey('filter', FILTERS).set(filter)
  ])

// positionWheel(positionNumber): go to a numbered slot directly (EngUI).
export const positionWheelCmd = (positionNumber: WheelPosition): Setup =>
  new Setup(PITFW_PREFIX, 'positionWheel', [
    choiceKey('positionNumber', WHEEL_POSITIONS).set(positionNumber)
  ])

// positionMotor(method, target, value): low-level engineering move. The value key
// matches the target — wheelPosition (Float, deg) for WHEEL, motorPosition (Int,
// count) for MOTOR — mirroring PositionMotorCommand's keys exactly (name AND type)
// so the assembly's param match succeeds. Counts are rounded to an integer.
export const positionMotorCmd = (
  method: PositionMethod,
  target: PositionTarget,
  value: number
): Setup =>
  new Setup(PITFW_PREFIX, 'positionMotor', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    choiceKey('positionTarget', POSITION_TARGETS).set(target),
    target === 'WHEEL'
      ? floatKey('wheelPosition', Units.degree).set([value])
      : intKey('motorPosition', Units.count).set([Math.round(value)])
  ])

// ---- Command gating: mirrors WheelAssemblyHandlers/validateCommand ------------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'selectFilter'
  | 'positionWheel'
  | 'positionMotor'
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

// Bound HCD (container ComponentInfo connection): controller 1, channel F
// (SDD Fig 2-2). Static label.
export const PITFW_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.1'
export const PITFW_HCD_LABEL = 'Galil HCD 1'

// Static mirror of ics-assemblies PitFilterWheel.conf, shown when the Config
// Service has no seeded active version. Simulator values, NOT calibrated; the
// position assignments are PROVISIONAL (confirm vs the actual install / RD).
export const PITFW_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "filterWheel" — rotating (SDD Table 7-1)',
    rows: [
      { label: 'Counts per revolution', value: '360000 (1000 counts/deg)' },
      { label: 'Is rotational', value: 'true' },
      { label: 'Rotational method', value: 'shortest' },
      { label: 'Default position', value: '1 (slot)' },
      { label: 'In-position threshold', value: '0.1 deg' },
      { label: 'Galil channel', value: 'F' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 7-1)',
    rows: [{ label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.1' }]
  },
  {
    title: 'Motion (SDD Table 7-1)',
    rows: [
      { label: 'Velocity', value: '360.0 deg/sec' },
      { label: 'Acceleration', value: '720.0 deg/sec²' },
      { label: 'Deceleration', value: '720.0 deg/sec²' },
      { label: 'Index offset', value: '0.0 deg' },
      { label: 'Index speed', value: '36.0 deg/sec' }
    ]
  },
  {
    title: 'Wheel position assignments (SDD Table 7-1) — PROVISIONAL',
    rows: [
      { label: 'Position 1', value: 'F890N' },
      { label: 'Position 2', value: 'F891N' },
      { label: 'Position 3', value: 'F850M' },
      { label: 'Position 4', value: 'F750W' },
      { label: 'Position 5', value: 'F810N' },
      { label: 'Position 6', value: 'F630N' },
      { label: 'Position 7', value: 'F865N' }
    ]
  }
]

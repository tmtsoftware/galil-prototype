/*
 * PshPupilMaskWheel model: constants, Setup builders, command gating and the
 * config snapshot for the APS.ICS.PSH.PupilMaskWheel assembly (SDD §7 Wheel
 * Assemblies).
 *
 * Built on the common PupilMaskWheelAssemblyHandlers (a single ROTATING
 * "pupilMaskWheel" axis), so it shares the telemetry shapes / readers / gating in
 * ./stage. The wheel-common commands are positionWheel (numbered slot 1-7) and
 * positionMotor (low-level engineering move); the pupil-mask-specific commands are
 * selectPupilMask and the engineering commandDetent. configure / home /
 * moveToDefaultPosition / stop / abortErrorRecovery are the common base commands.
 * Key names and choice domains mirror ics-assemblies PshPupilMaskWheelKeys.scala
 * EXACTLY — a mismatched name or key TYPE yields Invalid(MissingKey) at the assembly.
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

export const PSHPMW_PREFIX_STR = 'APS.ICS.PSH.PupilMaskWheel'
export const PSHPMW_PREFIX = Prefix.fromString(PSHPMW_PREFIX_STR)
export const PSHPMW_COMPONENT_ID = new ComponentId(PSHPMW_PREFIX, 'Assembly')

// Event names published by PshPupilMaskWheelHandlers.publishTelemetry: status + a
// single axisStatus (the latter carries detentState).
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const PSHPMW_CONFIG_PATH = 'APS/ICS/PSH/PupilMaskWheel.conf'

// ---- Choice domains — must match ChoiceKey.make(...) in the assembly keys -----
export const PUPIL_MASKS = ['PH-2-0', 'SH-0', 'SH-2', 'SH-5', 'Clear'] as const
export type PupilMask = (typeof PUPIL_MASKS)[number]

export const WHEEL_POSITIONS = ['1', '2', '3', '4', '5', '6', '7'] as const
export type WheelPosition = (typeof WHEEL_POSITIONS)[number]

export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

export const POSITION_TARGETS = ['WHEEL', 'MOTOR'] as const
export type PositionTarget = (typeof POSITION_TARGETS)[number]

export const DETENT_POSITIONS = ['EXTENDED', 'RETRACTED'] as const
export type DetentPosition = (typeof DETENT_POSITIONS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) -----
export const homeCmd = (): Setup => new Setup(PSHPMW_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(PSHPMW_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup =>
  new Setup(PSHPMW_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(PSHPMW_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup =>
  new Setup(PSHPMW_PREFIX, 'abortErrorRecovery')

// selectPupilMask(pupilMask): resolve a mask name to its assigned wheel slot.
export const selectPupilMaskCmd = (pupilMask: PupilMask): Setup =>
  new Setup(PSHPMW_PREFIX, 'selectPupilMask', [
    choiceKey('pupilMask', PUPIL_MASKS).set(pupilMask)
  ])

// positionWheel(positionNumber): go to a numbered slot directly (EngUI).
export const positionWheelCmd = (positionNumber: WheelPosition): Setup =>
  new Setup(PSHPMW_PREFIX, 'positionWheel', [
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
  new Setup(PSHPMW_PREFIX, 'positionMotor', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    choiceKey('positionTarget', POSITION_TARGETS).set(target),
    target === 'WHEEL'
      ? floatKey('wheelPosition', Units.degree).set([value])
      : intKey('motorPosition', Units.count).set([Math.round(value)])
  ])

// commandDetent(position): engineering/diagnostic detent drive (EXTENDED|RETRACTED).
export const commandDetentCmd = (position: DetentPosition): Setup =>
  new Setup(PSHPMW_PREFIX, 'commandDetent', [
    choiceKey('position', DETENT_POSITIONS).set(position)
  ])

// ---- Command gating: mirrors PupilMaskWheelAssemblyHandlers/validateCommand ----
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'selectPupilMask'
  | 'positionWheel'
  | 'positionMotor'
  | 'commandDetent'
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

// Bound HCD (container ComponentInfo connection): controller 1, channel C
// (SDD Fig 2-2). Static label.
export const PSHPMW_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.1'
export const PSHPMW_HCD_LABEL = 'Galil HCD 1'

// Static mirror of ics-assemblies PshPupilMaskWheel.conf, shown when the Config
// Service has no seeded active version. Simulator values, NOT calibrated; the
// position assignments are PROVISIONAL and the detent DIO addresses are DUMMY
// placeholders (confirm vs the actual install / RD / controller-1 wiring).
export const PSHPMW_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "pupilMaskWheel" — rotating (SDD Table 7-1)',
    rows: [
      { label: 'Counts per revolution', value: '360000 (1000 counts/deg)' },
      { label: 'Is rotational', value: 'true' },
      { label: 'Rotational method', value: 'shortest' },
      { label: 'Default position', value: '1 (slot)' },
      { label: 'In-position threshold', value: '0.1 deg' },
      { label: 'Galil channel', value: 'C' }
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
      { label: 'Position 1', value: 'PH-2-0' },
      { label: 'Position 2', value: 'SH-0' },
      { label: 'Position 3', value: 'SH-2' },
      { label: 'Position 4', value: 'SH-5' },
      { label: 'Position 5', value: 'Clear' }
    ]
  },
  {
    title: 'Detent DIO (SDD §7) — DUMMY placeholders, engineering-only',
    rows: [
      { label: 'Output bit (solenoid)', value: '1 (Galil 1-based)' },
      { label: 'Extended sensor input', value: '1 (Galil 1-based)' },
      { label: 'Retracted sensor input', value: '2 (Galil 1-based)' }
    ]
  }
]

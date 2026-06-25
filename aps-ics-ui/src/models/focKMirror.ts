/*
 * FocKMirror model: constants, Setup builders, command gating and the config
 * snapshot for the APS.ICS.FOC.KMirror assembly (SDD §8).
 *
 * Phase 1 (MANUAL): built on the common MotionAssemblyHandlers as a single
 * CONTINUOUS ROTATING "kMirror" axis (degrees), so it shares the telemetry shapes
 * / readers / gating in ./stage. The assembly-specific commands are positionKMirror
 * (ABSOLUTE/RELATIVE, degrees) and setMode; configure / home /
 * moveToDefaultPosition / stop / abortErrorRecovery are the common base commands.
 * Key names and choice domains mirror ics-assemblies FocKMirrorKeys.scala EXACTLY.
 *
 * SLEWING / TRACKING are SDD §8.2.2.3 / §8.2.2.4 and arrive with the Tracking
 * Control Actor in later phases; in Phase 1 setMode only offers MANUAL. The status
 * panel still reads mode / slewModeState / trackingModeState so the tracking-phase
 * telemetry surfaces are already wired.
 */
import {
  ComponentId,
  Prefix,
  Setup,
  choiceKey,
  floatKey,
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

export const KM_PREFIX_STR = 'APS.ICS.FOC.KMirror'
export const KM_PREFIX = Prefix.fromString(KM_PREFIX_STR)
export const KM_COMPONENT_ID = new ComponentId(KM_PREFIX, 'Assembly')

// Event names published by FocKMirrorHandlers.publishTelemetry: status + a single
// axisStatus.
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const KM_CONFIG_PATH = 'APS/ICS/FOC/KMirror.conf'

// ---- Choice domains — must match ChoiceKey.make(...) in the assembly keys -----
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]

// Full operating-mode domain (ICD). All three are commandable now; setMode(TRACKING)
// is additionally gated server-side on slewModeState=SLEW_COMPLETE.
export const MODES = ['MANUAL', 'SLEWING', 'TRACKING'] as const
export type Mode = (typeof MODES)[number]
export const MODES_AVAILABLE: readonly Mode[] = [
  'MANUAL',
  'SLEWING',
  'TRACKING'
]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) -----
export const homeCmd = (): Setup => new Setup(KM_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(KM_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup =>
  new Setup(KM_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(KM_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup =>
  new Setup(KM_PREFIX, 'abortErrorRecovery')

// positionKMirror(method, deg): manual/diagnostic move. Value key is `positionValue`
// (Float, degrees), mirroring PositionKMirrorCommand.positionValueKey exactly.
export const positionKMirrorCmd = (
  method: PositionMethod,
  deg: number
): Setup =>
  new Setup(KM_PREFIX, 'positionKMirror', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('positionValue', Units.degree).set([deg])
  ])

// setMode(mode): sets the operating mode. The choice domain is the full ICD set so
// the wire value validates; Phase 2 submits MANUAL or SLEWING (see MODES_AVAILABLE).
export const setModeCmd = (mode: Mode): Setup =>
  new Setup(KM_PREFIX, 'setMode', [choiceKey('mode', MODES).set(mode)])

// updatePitToPshOffset(deg): stores the static PIT-to-PSH rotation offset used in the
// slewing (and non-PIT tracking) demand. Key name mirrors the ICD exactly.
export const updatePitToPshOffsetCmd = (deg: number): Setup =>
  new Setup(KM_PREFIX, 'updatePitToPshOffset', [
    floatKey('pitToPshRotationOffset', Units.degree).set([deg])
  ])

// updatePitCorrectionOffset(deg): the running PIT loop's correction offset. The first
// one (in TRACKING) activates the PIT term in the demand (SDD §8.2.2.4).
export const updatePitCorrectionOffsetCmd = (deg: number): Setup =>
  new Setup(KM_PREFIX, 'updatePitCorrectionOffset', [
    floatKey('pitCorrectionOffset', Units.degree).set([deg])
  ])

// restartTracking(): continue tracking without the PIT loop (SDD §8.2.2.4). No params.
export const restartTrackingCmd = (): Setup =>
  new Setup(KM_PREFIX, 'restartTracking')

// ---- Command gating: mirrors FocKMirrorHandlers/validateCommand ---------------
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'positionKMirror'
  | 'setMode'
  | 'updatePitToPshOffset'
  | 'updatePitCorrectionOffset'
  | 'restartTracking'
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

// Bound HCD (container ComponentInfo connection): controller 3, channel A
// (SDD Fig 2-2). Static label.
export const KM_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.3'
export const KM_HCD_LABEL = 'Galil HCD 3'

// Static mirror of ics-assemblies FocKMirror.conf, shown when the Config Service
// has no seeded active version. Simulator values, NOT calibrated.
export const KM_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "kMirror" — continuous rotating (SDD §8)',
    rows: [
      { label: 'Counts per revolution', value: '360000 (1000 counts/deg)' },
      { label: 'Is rotational', value: 'true' },
      { label: 'Approach algorithm', value: 'forward' },
      { label: 'Default position', value: '0.0 deg (home/center)' },
      { label: 'In-position threshold', value: '0.1 deg' },
      { label: 'Galil channel', value: 'A' }
    ]
  },
  {
    title: 'Assembly → HCD binding',
    rows: [{ label: 'Galil HCD', value: 'APS.ICS.HCD.GalilMotion.3' }]
  },
  {
    title: 'Motion',
    rows: [
      { label: 'Velocity', value: '360.0 deg/sec' },
      { label: 'Acceleration', value: '720.0 deg/sec²' },
      { label: 'Deceleration', value: '720.0 deg/sec²' },
      { label: 'Index offset', value: '0.0 deg' },
      { label: 'Index speed', value: '36.0 deg/sec' }
    ]
  }
]

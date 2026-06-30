/*
 * CalibrationSourceStage model: constants, Setup builders, command gating, the
 * internalLightStatus reader and the config snapshot for the
 * APS.ICS.FOC.CalibrationSourceStage assembly.
 *
 * Built on the same base StageAssemblyHandlers as the InsertionStage (a single
 * linear "stage" axis), so it shares the telemetry shapes / readers / gating in
 * ./stage. It adds optic/slot/position commands plus light-source commands, and
 * a non-axis telemetry event (internalLightStatus). Key names and choice domains
 * mirror ics-assemblies CalibrationSourceStageKeys.scala EXACTLY.
 *
 * NOTE: the light path is STUBBED in the assembly this cut (controller-3 RIO not
 * wired). The light commands return Completed without I/O, and internalLightStatus
 * reflects COMMANDED light state, not a hardware readback.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import type { Event } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const CSS_PREFIX_STR = 'APS.ICS.FOC.CalibrationSourceStage'
export const CSS_PREFIX = Prefix.fromString(CSS_PREFIX_STR)
export const CSS_COMPONENT_ID = new ComponentId(CSS_PREFIX, 'Assembly')

// Event names published by CalibrationSourceStageHandlers.publishTelemetry:
// status + a single axisStatus + the non-axis internalLightStatus.
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'
export const LIGHT_EVENT = 'internalLightStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const CSS_CONFIG_PATH = 'APS/ICS/FOC/CalibrationSourceStage.conf'

// Choice domains — must match ChoiceKey.make(...) in the assembly keys.
export const OPTICS = ['CALIBRATION_SOURCE', 'ZERNIKE1', 'ZERNIKE2', 'FIELD_STOP', 'OPEN'] as const
export const SLOTS = ['1', '2', '3', '4', '5'] as const
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type Optic = (typeof OPTICS)[number]
export type Slot = (typeof SLOTS)[number]
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(CSS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(CSS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(CSS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(CSS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(CSS_PREFIX, 'abortErrorRecovery')

export const setOpticCmd = (optic: Optic): Setup =>
  new Setup(CSS_PREFIX, 'setOptic', [choiceKey('optic', OPTICS).set(optic)])

export const setSlotCmd = (slot: Slot): Setup =>
  new Setup(CSS_PREFIX, 'setSlot', [choiceKey('slotNumber', SLOTS).set(slot)])

export const setPositionCmd = (method: PositionMethod, mm: number): Setup =>
  new Setup(CSS_PREFIX, 'setPosition', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('positionValue', Units.millimeter).set([mm])
  ])

// setOpticAndSourceIntensity: position to the optic AND set the source (light is
// only on for CALIBRATION_SOURCE; the assembly turns it off for other optics).
export const setOpticAndSourceIntensityCmd = (optic: Optic, intensityPct: number): Setup =>
  new Setup(CSS_PREFIX, 'setOpticAndSourceIntensity', [
    choiceKey('optic', OPTICS).set(optic),
    floatKey('sourceIntensity').set([intensityPct])
  ])

export const setSourceIntensityCmd = (intensityPct: number): Setup =>
  new Setup(CSS_PREFIX, 'setSourceIntensity', [floatKey('sourceIntensity').set([intensityPct])])

// ---- internalLightStatus reader (non-axis event; STUB source in the assembly) -
export type LightSnapshot = {
  lightOn?: string // 'ON' | 'OFF'
  lightIntensity?: number // % of max
}

const firstValue = (e: Event, name: string): unknown =>
  e.paramSet.find((p) => p.keyName === name)?.values?.[0]

export const readLight = (e: Event): LightSnapshot => ({
  lightOn: firstValue(e, 'lightOn') as string | undefined,
  lightIntensity: firstValue(e, 'lightIntensity') as number | undefined
})

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----------
// Every specific command (optic/slot/position AND the light commands) is gated
// as 'motion' — i.e. requires OPERATIONAL — matching the assembly's base gate
// (PreHomed accepts only configure/home). The light path is not yet homing-
// independent; if that changes in the assembly, relax it here too.
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'setOptic'
  | 'setSlot'
  | 'setPosition'
  | 'setOpticAndSourceIntensity'
  | 'setSourceIntensity'
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

// Bound HCD (container ComponentInfo connection): controller 2, channel F — the
// STAGE MOTOR (SDD Fig 2-2). The light-source GPIO is on controller 3 but is
// stubbed this cut and not a tracked connection. Static label.
export const CSS_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.2'
export const CSS_HCD_LABEL = 'Galil HCD 2'

// Static mirror of ics-assemblies CalibrationSourceStage.conf, shown when the
// Config Service has no seeded active version. Simulator values, NOT calibrated.
export const CSS_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "stage" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Is rotational', value: 'false' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'In-position threshold', value: '0.01 mm' },
      { label: 'Galil channel', value: 'F (provisional)' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 6-1)',
    rows: [{ label: 'Galil HCD (stage motor)', value: 'APS.ICS.HCD.GalilMotion.2' }]
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
    title: 'Optic → slot (SDD Table 6-9)',
    rows: [
      { label: 'CALIBRATION_SOURCE', value: 'slot 1' },
      { label: 'ZERNIKE1', value: 'slot 2' },
      { label: 'ZERNIKE2', value: 'slot 3' },
      { label: 'FIELD_STOP', value: 'slot 4' },
      { label: 'OPEN', value: 'slot 5' }
    ]
  },
  {
    title: 'Slot → position mm (SDD Table 6-9)',
    rows: [
      { label: 'Slot 1', value: '-40.0 mm' },
      { label: 'Slot 2', value: '-20.0 mm' },
      { label: 'Slot 3', value: '0.0 mm' },
      { label: 'Slot 4', value: '20.0 mm' },
      { label: 'Slot 5', value: '40.0 mm' }
    ]
  },
  {
    title: 'Light source (SDD Table 6-9) — STUBBED, controller-3 RIO not wired',
    rows: [
      { label: 'Light source HCD', value: 'APS.ICS.HCD.GalilMotion.3' },
      { label: 'Max voltage (100%)', value: '10.0 V' },
      { label: 'On/off DIO address', value: '1' },
      { label: 'Output-select DIO address', value: '2' },
      { label: 'Voltage AO address', value: '1' }
    ]
  }
]

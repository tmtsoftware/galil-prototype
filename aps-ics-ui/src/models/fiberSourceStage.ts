/*
 * FiberSourceStage model: constants, Setup builders, command gating, the
 * internalLightStatus reader and the config snapshot for the
 * APS.ICS.STIM.FiberSourceStage assembly.
 *
 * Built on the same base StageAssemblyHandlers as the other stage assemblies
 * (three linear axes, X/Y/Z), so it shares the telemetry shapes / readers /
 * gating in ./stage. It adds positionSource (all three axes) plus a light-source
 * command, and a non-axis telemetry event (internalLightStatus). Key names and
 * choice domains mirror ics-assemblies StimFiberSourceStageKeys.scala EXACTLY.
 *
 * NOTE: the light path is STUBBED in the assembly this cut (controller-4 RIO not
 * wired). setSourceIntensity returns Completed without I/O, and
 * internalLightStatus reflects COMMANDED light state, not a hardware readback.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import type { Event } from '@tmtsoftware/esw-ts'
import { gateByKind } from './stage'
import type { CmdKind, ConfigSection, StatusSnapshot } from './stage'

export { readStatus, readAxis } from './stage'
export type { StatusSnapshot, AxisSnapshot, ConfigRow, ConfigSection } from './stage'

export const FSS_PREFIX_STR = 'APS.ICS.STIM.FiberSourceStage'
export const FSS_PREFIX = Prefix.fromString(FSS_PREFIX_STR)
export const FSS_COMPONENT_ID = new ComponentId(FSS_PREFIX, 'Assembly')

// Event names published by StimFiberSourceStageHandlers.publishTelemetry: status
// + a SEPARATE axis-status event per axis (x/y/z) + the non-axis
// internalLightStatus.
export const STATUS_EVENT = 'status'
export const X_AXIS_EVENT = 'xAxisStatus'
export const Y_AXIS_EVENT = 'yAxisStatus'
export const Z_AXIS_EVENT = 'zAxisStatus'
export const LIGHT_EVENT = 'internalLightStatus'

// Config Service path (prefix-mirrored: '.' -> '/', '<prefix>.conf').
export const FSS_CONFIG_PATH = 'APS/ICS/STIM/FiberSourceStage.conf'

// Choice domains — must match ChoiceKey.make(...) in the assembly keys.
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export const SOURCE_POWERS = ['ON', 'OFF'] as const
export type PositionMethod = (typeof POSITION_METHODS)[number]
export type SourcePower = (typeof SOURCE_POWERS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(FSS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(FSS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(FSS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(FSS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(FSS_PREFIX, 'abortErrorRecovery')

// positionSource: method + all three axis values, sent together (the assembly
// drives x/y/z from the single command).
export const positionSourceCmd = (
  method: PositionMethod,
  xMm: number,
  yMm: number,
  zMm: number
): Setup =>
  new Setup(FSS_PREFIX, 'positionSource', [
    choiceKey('positioningMethod', POSITION_METHODS).set(method),
    floatKey('positionValueX', Units.millimeter).set([xMm]),
    floatKey('positionValueY', Units.millimeter).set([yMm]),
    floatKey('positionValueZ', Units.millimeter).set([zMm])
  ])

// setSourceIntensity (STUB in the assembly): power ON/OFF + intensity %.
export const setSourceIntensityCmd = (power: SourcePower, intensityPct: number): Setup =>
  new Setup(FSS_PREFIX, 'setSourceIntensity', [
    choiceKey('sourcePower', SOURCE_POWERS).set(power),
    floatKey('sourceIntensity').set([intensityPct])
  ])

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
// Every specific command (positionSource AND the light command) is gated as
// 'motion' (requires OPERATIONAL), matching the assembly base gate.
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'positionSource'
  | 'setSourceIntensity'
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

// Bound HCD (container ComponentInfo connection): controller 4, channels A/B/C —
// the STAGE MOTORS (SDD Fig 2-2). The light-source DIO/AO is on controller 4 too
// but is stubbed this cut and not a tracked connection. Static label.
export const FSS_HCD_PREFIX_STR = 'APS.ICS.HCD.GalilMotion.4'
export const FSS_HCD_LABEL = 'Galil HCD 4'

// Static mirror of ics-assemblies FiberSourceStage.conf, shown when the Config
// Service has no seeded active version. Simulator values, NOT calibrated.
export const FSS_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Axis "xAxis" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'Galil channel', value: 'A (provisional)' }
    ]
  },
  {
    title: 'Axis "yAxis" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'Galil channel', value: 'B (provisional)' }
    ]
  },
  {
    title: 'Axis "zAxis" — linear (SDD Table 6-1)',
    rows: [
      { label: 'Counts per mm', value: '1000.0 counts/mm' },
      { label: 'Software limit (lower)', value: '-100.0 mm' },
      { label: 'Software limit (upper)', value: '100.0 mm' },
      { label: 'Default position', value: '0.0 mm' },
      { label: 'Galil channel', value: 'C (provisional)' }
    ]
  },
  {
    title: 'Assembly → HCD binding (SDD Table 6-1)',
    rows: [{ label: 'Galil HCD (stage motors)', value: 'APS.ICS.HCD.GalilMotion.4' }]
  },
  {
    title: 'Motion — all axes (SDD Table 6-1)',
    rows: [
      { label: 'Velocity', value: '20.0 mm/sec' },
      { label: 'Acceleration', value: '100.0 mm/sec²' },
      { label: 'Deceleration', value: '100.0 mm/sec²' },
      { label: 'Index offset', value: '100.0 mm' },
      { label: 'Index speed', value: '2.0 mm/sec' }
    ]
  },
  {
    title: 'Light source (SDD §6.10.2.2) — STUBBED, controller-4 RIO not wired',
    rows: [
      { label: 'Light source HCD', value: 'APS.ICS.HCD.GalilMotion.4' },
      { label: 'Max voltage (100%)', value: '10.0 V' },
      { label: 'On/off DIO address', value: '1' },
      { label: 'Output-select DIO address', value: '2' },
      { label: 'Voltage AO address', value: '1' }
    ]
  }
]

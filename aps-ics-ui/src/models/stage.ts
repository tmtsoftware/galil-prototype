/*
 * Shared stage-assembly model.
 *
 * The APS stage assemblies (InsertionStage, SteeringBeamSplitterStage, …) are
 * all built on the same base StageAssemblyHandlers, so they publish the SAME
 * telemetry key names and obey the SAME command-gating rules. This module holds
 * those shared pieces once; each per-assembly model imports them and adds only
 * its own prefix, command builders, choice domains and config snapshot.
 *
 * Key names mirror the assembly ICD EXACTLY — a mismatched name yields
 * Invalid(MissingKey) at the assembly (status/axis events) or simply reads as
 * undefined here.
 */
import type { Event } from '@tmtsoftware/esw-ts'

// ---- Telemetry snapshots (same key names across every stage assembly) -------
// status event:  assemblyState / hcdState / commandState
// *AxisStatus:    axisState / position / velocity / indexed / inPosition
export type StatusSnapshot = {
  assemblyState?: string
  hcdState?: string
  commandState?: string
  mode?: string // K-Mirror only: MANUAL | SLEWING | TRACKING
  slewModeState?: string // K-Mirror only: NOT_SLEWING | SLEWING | SLEW_COMPLETE
  trackingModeState?: string // K-Mirror only: NOT_TRACKING | NOT_CONVERGED | CONVERGED | ...
}
export type AxisSnapshot = {
  axisState?: string
  position?: number
  velocity?: number
  indexed?: boolean
  inPosition?: boolean
  wheelPositionNum?: number // rotating WHEEL assemblies only; achieved slot (-1 = unknown)
  detentState?: string // pupil-mask WHEEL assemblies only; EXTENDED | RETRACTED | OUT OF POSITION
}

const firstValue = (e: Event, name: string): unknown =>
  e.paramSet.find((p) => p.keyName === name)?.values?.[0]

export const readStatus = (e: Event): StatusSnapshot => ({
  assemblyState: firstValue(e, 'assemblyState') as string | undefined,
  hcdState: firstValue(e, 'hcdState') as string | undefined,
  commandState: firstValue(e, 'commandState') as string | undefined,
  mode: firstValue(e, 'mode') as string | undefined,
  slewModeState: firstValue(e, 'slewModeState') as string | undefined,
  trackingModeState: firstValue(e, 'trackingModeState') as string | undefined
})

export const readAxis = (e: Event): AxisSnapshot => ({
  axisState: firstValue(e, 'axisState') as string | undefined,
  position: firstValue(e, 'position') as number | undefined,
  velocity: firstValue(e, 'velocity') as number | undefined,
  indexed: firstValue(e, 'indexed') as boolean | undefined,
  inPosition: firstValue(e, 'inPosition') as boolean | undefined,
  wheelPositionNum: firstValue(e, 'wheelPositionNum') as number | undefined,
  detentState: firstValue(e, 'detentState') as string | undefined
})

// ---- Command gating (mirrors StageAssemblyHandlers.validateCommand) ---------
//  Faulted        -> reject all
//  Processing     -> reject all (assembly serialises commands)
//  ErrorRecovery  -> only abort (out-of-band)
//  PreHomed       -> only configure / home
//  Operational    -> motion commands
//
// Each assembly maps its own command names onto one of three kinds.
export type CmdKind = 'configHome' | 'motion' | 'abort'

export const gateByKind = (
  kind: CmdKind,
  s: StatusSnapshot,
  ready: boolean,
  busy: boolean
): boolean => {
  if (!ready || busy) return false
  if (s.assemblyState === 'FAULTED') return false
  if (s.commandState === 'PROCESSING') return false
  if (s.commandState === 'ERROR_RECOVERY') return kind === 'abort'
  if (kind === 'abort') return false
  if (kind === 'configHome') return true
  return s.assemblyState === 'OPERATIONAL' // motion commands require a homed axis
}

// ---- Read-only configuration snapshot rows (Configuration tab, SDD §4.4) ----
export type ConfigRow = { label: string; value: string }
export type ConfigSection = { title: string; rows: ConfigRow[] }
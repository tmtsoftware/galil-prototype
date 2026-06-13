/*
 * InsertionStage model: constants, Setup builders, event readers, and command
 * gating for the APS.ICS.STIM.InsertionStage assembly.
 *
 * Key names and choice domains mirror ics-assemblies InsertionStageKeys.scala
 * EXACTLY — a mismatched key name yields Invalid(MissingKey) at the assembly.
 */
import { ComponentId, Prefix, Setup, choiceKey, floatKey, Units } from '@tmtsoftware/esw-ts'
import type { Event } from '@tmtsoftware/esw-ts'

export const IS_PREFIX_STR = 'APS.ICS.STIM.InsertionStage'
export const IS_PREFIX = Prefix.fromString(IS_PREFIX_STR)
export const IS_COMPONENT_ID = new ComponentId(IS_PREFIX, 'Assembly')

// Event names published by InsertionStageHandlers.publishTelemetry
export const STATUS_EVENT = 'status'
export const AXIS_EVENT = 'axisStatus'

// Choice domains — must match ChoiceKey.make(...) in InsertionStageKeys.scala
export const LIGHT_SOURCES = ['SKY', 'STIMULUS'] as const
export const POSITION_METHODS = ['ABSOLUTE', 'RELATIVE'] as const
export type LightSource = (typeof LIGHT_SOURCES)[number]
export type PositionMethod = (typeof POSITION_METHODS)[number]

// ---- Setup builders (paramSet shape mirrors the assembly's command keys) ----
export const homeCmd = (): Setup => new Setup(IS_PREFIX, 'home')
export const configureCmd = (): Setup => new Setup(IS_PREFIX, 'configure')
export const moveToDefaultCmd = (): Setup => new Setup(IS_PREFIX, 'moveToDefaultPosition')
export const stopCmd = (): Setup => new Setup(IS_PREFIX, 'stop')
export const abortRecoveryCmd = (): Setup => new Setup(IS_PREFIX, 'abortErrorRecovery')

export const selectSourceCmd = (src: LightSource): Setup =>
  new Setup(IS_PREFIX, 'selectSource', [choiceKey('lightSource', LIGHT_SOURCES).set(src)])

export const positionStageCmd = (method: PositionMethod, mm: number): Setup =>
  new Setup(IS_PREFIX, 'positionStage', [
    choiceKey('positionMethod', POSITION_METHODS).set(method),
    floatKey('value', Units.millimeter).set([mm])
  ])

// ---- Event reading: pull the first value of a param by name ----
const firstValue = (e: Event, name: string): unknown =>
  e.paramSet.find((p) => p.keyName === name)?.values?.[0]

export type StatusSnapshot = {
  assemblyState?: string
  hcdState?: string
  commandState?: string
}
export type AxisSnapshot = {
  axisState?: string
  position?: number
  velocity?: number
  indexed?: boolean
  inPosition?: boolean
}

export const readStatus = (e: Event): StatusSnapshot => ({
  assemblyState: firstValue(e, 'assemblyState') as string | undefined,
  hcdState: firstValue(e, 'hcdState') as string | undefined,
  commandState: firstValue(e, 'commandState') as string | undefined
})

export const readAxis = (e: Event): AxisSnapshot => ({
  axisState: firstValue(e, 'axisState') as string | undefined,
  position: firstValue(e, 'position') as number | undefined,
  velocity: firstValue(e, 'velocity') as number | undefined,
  indexed: firstValue(e, 'indexed') as boolean | undefined,
  inPosition: firstValue(e, 'inPosition') as boolean | undefined
})

// ---- Command gating: mirrors StageAssemblyHandlers.validateCommand ----
//  Faulted        -> reject all
//  Processing     -> reject all (assembly serialises commands)
//  ErrorRecovery  -> only abortErrorRecovery
//  PreHomed       -> only configure / home
//  Operational    -> all motion commands
export type CmdName =
  | 'configure'
  | 'home'
  | 'moveToDefaultPosition'
  | 'selectSource'
  | 'positionStage'
  | 'stop'
  | 'abortErrorRecovery'

export const commandEnabled = (
  cmd: CmdName,
  s: StatusSnapshot,
  ready: boolean,
  busy: boolean
): boolean => {
  if (!ready || busy) return false
  if (s.assemblyState === 'FAULTED') return false
  if (s.commandState === 'PROCESSING') return false
  if (s.commandState === 'ERROR_RECOVERY') return cmd === 'abortErrorRecovery'
  if (cmd === 'abortErrorRecovery') return false
  if (cmd === 'configure' || cmd === 'home') return true
  return s.assemblyState === 'OPERATIONAL' // motion commands require a homed axis
}
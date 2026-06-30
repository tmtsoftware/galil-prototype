/*
 * Shared detector-assembly model.
 *
 * The three APS detector assemblies (APT / PIT / PSH) are all built on the same
 * base DetectorAssemblyHandlers, so they publish the SAME `status` /
 * `temperatureStatus` shapes and obey the SAME command-gating rules. This module
 * holds those shared pieces once; each per-detector model imports them and adds
 * its own prefix, command builders, choice domains and config snapshot.
 *
 * The `status` event (assemblyState / coolingHealth / cameraPresent /
 * cameraAcquisitionState) is read by Main via the shared readStatus in ./stage
 * (extended with the detector fields). The remaining events — temperatureStatus,
 * setupStatus, configStatus and (APT) guidingStatus — are carried as extraEvents
 * and read here. Key names mirror the *DetectorKeys EXACTLY.
 */
import type { Event } from '@tmtsoftware/esw-ts'
import type { StatusSnapshot } from './stage'

export { readStatus } from './stage'
export type { StatusSnapshot, ConfigRow, ConfigSection } from './stage'

const firstValue = (e: Event, name: string): unknown =>
  e.paramSet.find((p) => p.keyName === name)?.values?.[0]

// ---- temperatureStatus -----------------------------------------------------
export type TemperatureSnapshot = {
  detectorTemperature?: number
  temperatureSetPoint?: number
}
export const readTemperature = (e: Event): TemperatureSnapshot => ({
  detectorTemperature: firstValue(e, 'detectorTemperature') as number | undefined,
  temperatureSetPoint: firstValue(e, 'temperatureSetPoint') as number | undefined
})

// ---- setupStatus (superset of APT + PIT/PSH fields; absent keys read undefined)
export type SetupSnapshot = {
  imageSize?: number
  acquisitionMode?: string
  bufferModel?: string
  frameRate?: number
  path?: string
  hBin?: number
  vBin?: number
  integrationTime?: number // PIT/PSH only
  roiStartRow?: number // PIT/PSH only
  roiStartCol?: number // PIT/PSH only
  roiHeight?: number // PIT/PSH only
  roiWidth?: number // PIT/PSH only
}
export const readSetup = (e: Event): SetupSnapshot => ({
  imageSize: firstValue(e, 'imageSize') as number | undefined,
  acquisitionMode: firstValue(e, 'acquisitionMode') as string | undefined,
  bufferModel: firstValue(e, 'bufferModel') as string | undefined,
  frameRate: firstValue(e, 'frameRate') as number | undefined,
  path: firstValue(e, 'path') as string | undefined,
  hBin: firstValue(e, 'hBin') as number | undefined,
  vBin: firstValue(e, 'vBin') as number | undefined,
  integrationTime: firstValue(e, 'integrationTime') as number | undefined,
  roiStartRow: firstValue(e, 'roiStartRow') as number | undefined,
  roiStartCol: firstValue(e, 'roiStartCol') as number | undefined,
  roiHeight: firstValue(e, 'roiHeight') as number | undefined,
  roiWidth: firstValue(e, 'roiWidth') as number | undefined
})

// ---- configStatus (APT fields + PIT/PSH fields; absent keys read undefined) -
export type ConfigSnapshot = {
  // APT
  pixelEncoding?: string
  pixelReadoutRate?: string
  spuriousNoiseFilter?: boolean
  // PIT/PSH
  analogGain?: string
  bitDepth?: string
  shutterMode?: string
  cms?: string
}
export const readConfig = (e: Event): ConfigSnapshot => ({
  pixelEncoding: firstValue(e, 'pixelEncoding') as string | undefined,
  pixelReadoutRate: firstValue(e, 'pixelReadoutRate') as string | undefined,
  spuriousNoiseFilter: firstValue(e, 'spuriousNoiseFilter') as boolean | undefined,
  analogGain: firstValue(e, 'analogGain') as string | undefined,
  bitDepth: firstValue(e, 'bitDepth') as string | undefined,
  shutterMode: firstValue(e, 'shutterMode') as string | undefined,
  cms: firstValue(e, 'cms') as string | undefined
})

// ---- guidingStatus (APT only) ----------------------------------------------
export type GuidingSnapshot = {
  gainMode?: string
  integrationTime?: number
  roiStartRow?: number
  roiStartCol?: number
  roiHeight?: number
  roiWidth?: number
}
export const readGuiding = (e: Event): GuidingSnapshot => ({
  gainMode: firstValue(e, 'gainMode') as string | undefined,
  integrationTime: firstValue(e, 'integrationTime') as number | undefined,
  roiStartRow: firstValue(e, 'roiStartRow') as number | undefined,
  roiStartCol: firstValue(e, 'roiStartCol') as number | undefined,
  roiHeight: firstValue(e, 'roiHeight') as number | undefined,
  roiWidth: firstValue(e, 'roiWidth') as number | undefined
})

// ---- Command gating (mirrors DetectorAssemblyHandlers.validateCommand) ------
//  Faulted               -> only recover / resetCamera
//  camera BUSY           -> nothing (UI `busy` flag is also set for the whole
//                           exposure, so the panel is disabled anyway)
//  camera STREAMING/PAUSED-> loop control + abort + recover/resetCamera
//  IDLE / READY          -> config + exposure commands
//
// Each detector maps its own command names onto one of these kinds.
export type DetCmdKind = 'config' | 'expose' | 'loopControl' | 'abort' | 'recover'

export const detectorGate = (
  kind: DetCmdKind,
  s: StatusSnapshot,
  ready: boolean,
  busy: boolean
): boolean => {
  if (!ready || busy) return false
  if (kind === 'recover') return true // recover / resetCamera available whenever idle of an in-flight command
  if (s.assemblyState === 'FAULTED') return false
  const acq = s.cameraAcquisitionState
  if (acq === 'STREAMING' || acq === 'PAUSED') return kind === 'loopControl' || kind === 'abort'
  return kind === 'config' || kind === 'expose'
}

/*
 * APT Detector model (APS.ICS.APT.Detector): constants, Setup builders, command
 * gating and the config snapshot. Command/param names mirror AptDetectorKeys
 * EXACTLY — note APT uses `configDetector` / `configDetectorCooling` (PIT/PSH use
 * `configure*`) and the lowercase ROI param names (roistartRow / roistartCol /
 * roiwidth / roiheight).
 */
import { ComponentId, Prefix, Setup, booleanKey, choiceKey, floatKey, intKey, Units } from '@tmtsoftware/esw-ts'
import { detectorGate } from './detector'
import type { DetCmdKind, StatusSnapshot, ConfigSection } from './detector'

export { readStatus, readTemperature, readSetup, readConfig, readGuiding } from './detector'
export type {
  StatusSnapshot,
  TemperatureSnapshot,
  SetupSnapshot,
  ConfigSnapshot,
  GuidingSnapshot,
  ConfigSection
} from './detector'

export const APT_PREFIX_STR = 'APS.ICS.APT.Detector'
export const APT_PREFIX = Prefix.fromString(APT_PREFIX_STR)
export const APT_COMPONENT_ID = new ComponentId(APT_PREFIX, 'Assembly')

export const STATUS_EVENT = 'status'
export const TEMPERATURE_EVENT = 'temperatureStatus'
export const SETUP_EVENT = 'setupStatus'
export const CONFIG_EVENT = 'configStatus'
export const GUIDING_EVENT = 'guidingStatus'

export const APT_CONFIG_PATH = 'APS/ICS/APT/Detector.conf'

// Choice domains — must match ChoiceKey.make(...) in AptDetectorKeys.
export const GAIN_MODES = ['12-BIT', '16-BIT'] as const
export const FAN_SPEEDS = ['OFF', 'LOW', 'MEDIUM', 'HIGH'] as const
export const RECOVER_MODES = ['CLEAR', 'RESET', 'REBOOT'] as const
export type GainMode = (typeof GAIN_MODES)[number]
export type FanSpeed = (typeof FAN_SPEEDS)[number]
export type RecoverMode = (typeof RECOVER_MODES)[number]

// ---- Setup builders (paramSet shape mirrors AptDetectorKeys command keys) ----
export const configDetectorCoolingCmd = (setPoint: number, fan: FanSpeed): Setup =>
  new Setup(APT_PREFIX, 'configDetectorCooling', [
    floatKey('temperatureSetPoint', Units.degC).set([setPoint]),
    choiceKey('fanSpeed', FAN_SPEEDS).set(fan)
  ])

export const configDetectorCmd = (
  startRow: number,
  startCol: number,
  width: number,
  height: number,
  hBin: number,
  vBin: number,
  gainMode: GainMode
): Setup =>
  new Setup(APT_PREFIX, 'configDetector', [
    intKey('roistartRow', Units.pix).set([startRow]),
    intKey('roistartCol', Units.pix).set([startCol]),
    intKey('roiwidth', Units.pix).set([width]),
    intKey('roiheight', Units.pix).set([height]),
    intKey('hBin', Units.pix).set([hBin]),
    intKey('vBin', Units.pix).set([vBin]),
    choiceKey('gainMode', GAIN_MODES).set(gainMode)
  ])

export const setDefaultConfigurationCmd = (): Setup => new Setup(APT_PREFIX, 'setDefaultConfiguration')

export const takeAndPublishExposureCmd = (integrationTime: number, gainMode: GainMode): Setup =>
  new Setup(APT_PREFIX, 'takeAndPublishExposure', [
    floatKey('integrationTime', Units.second).set([integrationTime]),
    choiceKey('gainMode', GAIN_MODES).set(gainMode)
  ])

export const takeAndStoreExposureCmd = (integrationTime: number, gainMode: GainMode): Setup =>
  new Setup(APT_PREFIX, 'takeAndStoreExposure', [
    floatKey('integrationTime', Units.second).set([integrationTime]),
    choiceKey('gainMode', GAIN_MODES).set(gainMode)
  ])

export const startExposureLoopCmd = (integrationTime: number, rate: number, gainMode: GainMode): Setup =>
  new Setup(APT_PREFIX, 'startExposureLoop', [
    floatKey('integrationTime', Units.second).set([integrationTime]),
    floatKey('rate', Units.hertz).set([rate]),
    choiceKey('gainMode', GAIN_MODES).set(gainMode)
  ])

export const stopExposureLoopCmd = (): Setup => new Setup(APT_PREFIX, 'stopExposureLoop')
export const pauseExposureLoopCmd = (): Setup => new Setup(APT_PREFIX, 'pauseExposureLoop')
export const restartExposureLoopCmd = (): Setup => new Setup(APT_PREFIX, 'restartExposureLoop')

export const takeHighSpeedExposuresCmd = (
  integrationTime: number,
  gainMode: GainMode,
  roiIdentifier: number,
  frameRate: number,
  duration: number
): Setup =>
  new Setup(APT_PREFIX, 'takeHighSpeedExposures', [
    floatKey('integrationTime', Units.second).set([integrationTime]),
    choiceKey('gainMode', GAIN_MODES).set(gainMode),
    intKey('roiIdentifier').set([roiIdentifier]),
    intKey('frameRate').set([frameRate]),
    intKey('duration', Units.second).set([duration])
  ])

export const abortHighSpeedExposureCmd = (): Setup => new Setup(APT_PREFIX, 'abortHighSpeedExposure')

export const recoverCmd = (mode: RecoverMode, autoResume: boolean): Setup =>
  new Setup(APT_PREFIX, 'recover', [
    choiceKey('mode', RECOVER_MODES).set(mode),
    booleanKey('autoResume').set([autoResume])
  ])

export const resetCameraCmd = (): Setup => new Setup(APT_PREFIX, 'resetCamera')

// ---- Command gating: mirrors DetectorAssemblyHandlers.validateCommand --------
export type CmdName =
  | 'configDetectorCooling'
  | 'configDetector'
  | 'setDefaultConfiguration'
  | 'takeAndPublishExposure'
  | 'takeAndStoreExposure'
  | 'startExposureLoop'
  | 'stopExposureLoop'
  | 'pauseExposureLoop'
  | 'restartExposureLoop'
  | 'takeHighSpeedExposures'
  | 'abortHighSpeedExposure'
  | 'recover'
  | 'resetCamera'

const kindOf = (cmd: CmdName): DetCmdKind => {
  switch (cmd) {
    case 'configDetectorCooling':
    case 'configDetector':
    case 'setDefaultConfiguration':
      return 'config'
    case 'takeAndPublishExposure':
    case 'takeAndStoreExposure':
    case 'startExposureLoop':
    case 'takeHighSpeedExposures':
      return 'expose'
    case 'stopExposureLoop':
    case 'pauseExposureLoop':
    case 'restartExposureLoop':
      return 'loopControl'
    case 'abortHighSpeedExposure':
      return 'abort'
    case 'recover':
    case 'resetCamera':
      return 'recover'
  }
}

export const commandEnabled = (cmd: CmdName, s: StatusSnapshot, ready: boolean, busy: boolean): boolean =>
  detectorGate(kindOf(cmd), s, ready, busy)

// ---- Selector label + read-only config snapshot -----------------------------
export const APT_DETECTOR_LABEL = 'Detector (Andor, MOCK)'

export const APT_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Detector — MOCK (SDD §5.1) — NOT calibrated',
    rows: [
      { label: 'Camera', value: 'Andor acquisition/guiding (mock)' },
      { label: 'Image transfer', value: 'synthetic in-memory frame (no HCD)' },
      { label: 'Image publishing', value: 'VBDS — stubbed (no esw-vbds dependency)' }
    ]
  },
  {
    title: 'Default ROI / binning (Table 5-3)',
    rows: [
      { label: 'ROI', value: '256 x 256 @ (0, 0)' },
      { label: 'Binning', value: '1 x 1' },
      { label: 'Gain mode', value: '12-BIT' }
    ]
  },
  {
    title: 'Default config / cooling',
    rows: [
      { label: 'Pixel encoding', value: 'Mono16' },
      { label: 'Pixel readout rate', value: '100MHz' },
      { label: 'Spurious noise filter', value: 'false' },
      { label: 'Temperature set point', value: '-40.0 degC' },
      { label: 'Fan speed', value: 'MEDIUM' },
      { label: 'Guiding frame rate', value: '10.0 Hz' }
    ]
  },
  {
    title: 'VBDS (stubbed)',
    rows: [
      { label: 'Stream', value: 'APS-APT-ACQ' },
      { label: 'Content type', value: 'image/fits' }
    ]
  }
]

/*
 * PIT Detector model (APS.ICS.PIT.Detector): constants, Setup builders, command
 * gating and the config snapshot. Names mirror PitDetectorKeys EXACTLY — PIT uses
 * `configureDetector` / `configureDetectorCooling`, `analogGainMode` (LOW/HIGH/HDR)
 * and the ROI start-col param is named `rotStartCol` (ICD typo, preserved).
 */
import { ComponentId, Prefix, Setup, booleanKey, choiceKey, floatKey, intKey, Units } from '@tmtsoftware/esw-ts'
import { detectorGate } from './detector'
import type { DetCmdKind, StatusSnapshot, ConfigSection } from './detector'

export { readStatus, readTemperature, readSetup, readConfig } from './detector'
export type {
  StatusSnapshot,
  TemperatureSnapshot,
  SetupSnapshot,
  ConfigSnapshot,
  ConfigSection
} from './detector'

export const PIT_PREFIX_STR = 'APS.ICS.PIT.Detector'
export const PIT_PREFIX = Prefix.fromString(PIT_PREFIX_STR)
export const PIT_COMPONENT_ID = new ComponentId(PIT_PREFIX, 'Assembly')

export const STATUS_EVENT = 'status'
export const TEMPERATURE_EVENT = 'temperatureStatus'
export const SETUP_EVENT = 'setupStatus'
export const CONFIG_EVENT = 'configStatus'

export const PIT_CONFIG_PATH = 'APS/ICS/PIT/Detector.conf'

// Choice domains — must match ChoiceKey.make(...) in PitDetectorKeys.
export const ANALOG_GAINS = ['LOW', 'HIGH', 'HDR'] as const
export const BIT_DEPTHS = ['14-bit', '16-bit'] as const
export const SHUTTER_MODES = ['ROLLING', 'GLOBAL'] as const // command domain (configStatus is Rolling/Global)
export const CMS_MODES = ['ON', 'OFF'] as const
export const RECOVER_MODES = ['CLEAR', 'RESET', 'REBOOT'] as const
export type AnalogGain = (typeof ANALOG_GAINS)[number]
export type BitDepth = (typeof BIT_DEPTHS)[number]
export type ShutterMode = (typeof SHUTTER_MODES)[number]
export type CmsMode = (typeof CMS_MODES)[number]
export type RecoverMode = (typeof RECOVER_MODES)[number]

// ---- Setup builders ---------------------------------------------------------
export const configureDetectorCoolingCmd = (setPoint: number): Setup =>
  new Setup(PIT_PREFIX, 'configureDetectorCooling', [floatKey('temperatureSetPoint', Units.degC).set([setPoint])])

export const configureDetectorCmd = (
  startRow: number,
  startCol: number,
  width: number,
  height: number,
  hBin: number,
  vBin: number,
  analogGainMode: AnalogGain,
  bitDepth: BitDepth,
  shutterMode: ShutterMode,
  cms: CmsMode
): Setup =>
  new Setup(PIT_PREFIX, 'configureDetector', [
    intKey('roiStartRow', Units.pix).set([startRow]),
    intKey('rotStartCol', Units.pix).set([startCol]), // ICD key name typo, preserved
    intKey('roiWidth', Units.pix).set([width]),
    intKey('roiHeight', Units.pix).set([height]),
    intKey('hBin', Units.pix).set([hBin]),
    intKey('vBin', Units.pix).set([vBin]),
    choiceKey('analogGainMode', ANALOG_GAINS).set(analogGainMode),
    choiceKey('bitDepth', BIT_DEPTHS).set(bitDepth),
    choiceKey('shutterMode', SHUTTER_MODES).set(shutterMode),
    choiceKey('cms', CMS_MODES).set(cms)
  ])

export const setDefaultConfigurationCmd = (): Setup => new Setup(PIT_PREFIX, 'setDefaultConfiguration')

export const takeExposureCmd = (integrationTime: number, analogGainMode: AnalogGain): Setup =>
  new Setup(PIT_PREFIX, 'takeExposure', [
    floatKey('integrationTime', Units.second).set([integrationTime]),
    choiceKey('analogGainMode', ANALOG_GAINS).set(analogGainMode)
  ])

export const takeAndStoreExposureCmd = (integrationTime: number, analogGainMode: AnalogGain): Setup =>
  new Setup(PIT_PREFIX, 'takeAndStoreExposure', [
    floatKey('integrationTime', Units.second).set([integrationTime]),
    choiceKey('analogGainMode', ANALOG_GAINS).set(analogGainMode)
  ])

export const storeExposureCmd = (): Setup => new Setup(PIT_PREFIX, 'storeExposure')
export const abortExposureCmd = (): Setup => new Setup(PIT_PREFIX, 'abortExposure')

export const recoverCmd = (mode: RecoverMode, autoResume: boolean): Setup =>
  new Setup(PIT_PREFIX, 'recover', [
    choiceKey('mode', RECOVER_MODES).set(mode),
    booleanKey('autoResume').set([autoResume])
  ])

export const resetCameraCmd = (): Setup => new Setup(PIT_PREFIX, 'resetCamera')

// ---- Command gating ---------------------------------------------------------
export type CmdName =
  | 'configureDetectorCooling'
  | 'configureDetector'
  | 'setDefaultConfiguration'
  | 'takeExposure'
  | 'takeAndStoreExposure'
  | 'storeExposure'
  | 'abortExposure'
  | 'recover'
  | 'resetCamera'

const kindOf = (cmd: CmdName): DetCmdKind => {
  switch (cmd) {
    case 'configureDetectorCooling':
    case 'configureDetector':
    case 'setDefaultConfiguration':
      return 'config'
    case 'takeExposure':
    case 'takeAndStoreExposure':
    case 'storeExposure':
      return 'expose'
    case 'abortExposure':
      return 'abort'
    case 'recover':
    case 'resetCamera':
      return 'recover'
  }
}

export const commandEnabled = (cmd: CmdName, s: StatusSnapshot, ready: boolean, busy: boolean): boolean =>
  detectorGate(kindOf(cmd), s, ready, busy)

// ---- Selector label + read-only config snapshot -----------------------------
export const PIT_DETECTOR_LABEL = 'Detector (Teledyne, MOCK)'

export const PIT_CONFIG_VIEW: ConfigSection[] = [
  {
    title: 'Detector — MOCK (SDD §5.2) — NOT calibrated',
    rows: [
      { label: 'Camera', value: 'Teledyne single-exposure (mock)' },
      { label: 'Image transfer', value: 'synthetic in-memory frame (no HCD)' },
      { label: 'Real path', value: 'mmap → ICS Computation (not reproduced)' }
    ]
  },
  {
    title: 'Default ROI / binning (Table 5-9)',
    rows: [
      { label: 'ROI', value: '256 x 256 @ (0, 0)' },
      { label: 'Binning', value: '1 x 1' }
    ]
  },
  {
    title: 'Default config / cooling',
    rows: [
      { label: 'Analog gain', value: 'HIGH' },
      { label: 'Bit depth', value: '16-bit' },
      { label: 'Shutter mode', value: 'Rolling' },
      { label: 'CMS', value: 'OFF' },
      { label: 'Integration time', value: '0.5 s' },
      { label: 'Temperature set point', value: '-30.0 degC' },
      { label: 'Fan speed', value: 'MEDIUM' }
    ]
  }
]

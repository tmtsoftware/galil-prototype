/*
 * Component registry — the single list of ICS components the UI can drive.
 *
 * Each descriptor captures everything Main needs to be component-agnostic:
 *   - identity:   key (= prefix string), label, prefix, componentId
 *   - config:     configPath (Config Service) + staticConfig (snapshot fallback)
 *   - telemetry:  statusEvent + axisEvents (the event names to subscribe)
 *   - rendering:  renderCommands / renderStatus adapters that map Main's generic
 *                 props onto each component's own panel props
 *
 * Main resolves the selected key to a descriptor and drives services,
 * subscriptions, the lifecycle poll and the config fetch off it. Adding a
 * component is: add its model + panels, then add one entry here.
 */
import React from 'react'
import type { Event, Setup, SupervisorLifecycleState } from '@tmtsoftware/esw-ts'
import type { AxisSnapshot, ConfigSection, StatusSnapshot } from '../models/stage'

import { InsertionStageCommands } from './InsertionStageCommands'
import { InsertionStageStatus } from './InsertionStageStatus'
import {
  AXIS_EVENT as IS_AXIS_EVENT,
  STATUS_EVENT as IS_STATUS_EVENT,
  IS_COMPONENT_ID,
  IS_CONFIG_PATH,
  IS_CONFIG_VIEW,
  IS_PREFIX,
  IS_PREFIX_STR
} from '../models/insertionStage'

import { SteeringBeamSplitterCommands } from './SteeringBeamSplitterCommands'
import { SteeringBeamSplitterStatus } from './SteeringBeamSplitterStatus'
import {
  STATUS_EVENT as SBS_STATUS_EVENT,
  X_AXIS_EVENT as SBS_X_AXIS_EVENT,
  Y_AXIS_EVENT as SBS_Y_AXIS_EVENT,
  SBS_COMPONENT_ID,
  SBS_CONFIG_PATH,
  SBS_CONFIG_VIEW,
  SBS_PREFIX,
  SBS_PREFIX_STR
} from '../models/steeringBeamSplitter'

import { CollimatorUnitCommands } from './CollimatorUnitCommands'
import { CollimatorUnitStatus } from './CollimatorUnitStatus'
import {
  STATUS_EVENT as CU_STATUS_EVENT,
  FRONT_AXIS_EVENT as CU_FRONT_AXIS_EVENT,
  REAR_AXIS_EVENT as CU_REAR_AXIS_EVENT,
  CU_COMPONENT_ID,
  CU_CONFIG_PATH,
  CU_CONFIG_VIEW,
  CU_PREFIX,
  CU_PREFIX_STR
} from '../models/collimatorUnit'

import { CalibrationSourceStageCommands } from './CalibrationSourceStageCommands'
import { CalibrationSourceStageStatus } from './CalibrationSourceStageStatus'
import {
  STATUS_EVENT as CSS_STATUS_EVENT,
  AXIS_EVENT as CSS_AXIS_EVENT,
  LIGHT_EVENT as CSS_LIGHT_EVENT,
  readLight as cssReadLight,
  CSS_COMPONENT_ID,
  CSS_CONFIG_PATH,
  CSS_CONFIG_VIEW,
  CSS_PREFIX,
  CSS_PREFIX_STR
} from '../models/calibrationSourceStage'

import { PshFocusStageCommands } from './PshFocusStageCommands'
import { PshFocusStageStatus } from './PshFocusStageStatus'
import {
  STATUS_EVENT as PSHFS_STATUS_EVENT,
  AXIS_EVENT as PSHFS_AXIS_EVENT,
  PSHFS_COMPONENT_ID,
  PSHFS_CONFIG_PATH,
  PSHFS_CONFIG_VIEW,
  PSHFS_PREFIX,
  PSHFS_PREFIX_STR
} from '../models/pshFocusStage'

import { PitFocusStageCommands } from './PitFocusStageCommands'
import { PitFocusStageStatus } from './PitFocusStageStatus'
import {
  STATUS_EVENT as PITFS_STATUS_EVENT,
  AXIS_EVENT as PITFS_AXIS_EVENT,
  PITFS_COMPONENT_ID,
  PITFS_CONFIG_PATH,
  PITFS_CONFIG_VIEW,
  PITFS_PREFIX,
  PITFS_PREFIX_STR
} from '../models/pitFocusStage'

import { AptFocusStageCommands } from './AptFocusStageCommands'
import { AptFocusStageStatus } from './AptFocusStageStatus'
import {
  STATUS_EVENT as APTFS_STATUS_EVENT,
  AXIS_EVENT as APTFS_AXIS_EVENT,
  APTFS_COMPONENT_ID,
  APTFS_CONFIG_PATH,
  APTFS_CONFIG_VIEW,
  APTFS_PREFIX,
  APTFS_PREFIX_STR
} from '../models/aptFocusStage'

import { TiltPlateCommands } from './TiltPlateCommands'
import { TiltPlateStatus } from './TiltPlateStatus'
import {
  STATUS_EVENT as TP_STATUS_EVENT,
  X_AXIS_EVENT as TP_X_AXIS_EVENT,
  Y_AXIS_EVENT as TP_Y_AXIS_EVENT,
  TP_COMPONENT_ID,
  TP_CONFIG_PATH,
  TP_CONFIG_VIEW,
  TP_PREFIX,
  TP_PREFIX_STR
} from '../models/tiltPlate'

import { FiberSourceStageCommands } from './FiberSourceStageCommands'
import { FiberSourceStageStatus } from './FiberSourceStageStatus'
import {
  STATUS_EVENT as FSS_STATUS_EVENT,
  X_AXIS_EVENT as FSS_X_AXIS_EVENT,
  Y_AXIS_EVENT as FSS_Y_AXIS_EVENT,
  Z_AXIS_EVENT as FSS_Z_AXIS_EVENT,
  LIGHT_EVENT as FSS_LIGHT_EVENT,
  readLight as fssReadLight,
  FSS_COMPONENT_ID,
  FSS_CONFIG_PATH,
  FSS_CONFIG_VIEW,
  FSS_PREFIX,
  FSS_PREFIX_STR
} from '../models/fiberSourceStage'

import { PupilMaskStageCommands } from './PupilMaskStageCommands'
import { PupilMaskStageStatus } from './PupilMaskStageStatus'
import {
  STATUS_EVENT as PMS_STATUS_EVENT,
  X_AXIS_EVENT as PMS_X_AXIS_EVENT,
  Y_AXIS_EVENT as PMS_Y_AXIS_EVENT,
  PHI_AXIS_EVENT as PMS_PHI_AXIS_EVENT,
  PMS_COMPONENT_ID,
  PMS_CONFIG_PATH,
  PMS_CONFIG_VIEW,
  PMS_PREFIX,
  PMS_PREFIX_STR
} from '../models/pupilMaskStage'

// esw-ts exports Prefix/ComponentId as classes; type them off the model values
// so this compiles both against the real package and the stubbed harness.
type PrefixT = typeof IS_PREFIX
type ComponentIdT = typeof IS_COMPONENT_ID

export type CommandsProps = {
  status: StatusSnapshot
  ready: boolean
  busy: boolean
  run: (s: Setup, label: string) => void
}
export type StatusProps = {
  status: StatusSnapshot
  axes: Record<string, AxisSnapshot>
  extras: Record<string, Event>
  lifecycle?: SupervisorLifecycleState
}

export type ComponentDescriptor = {
  key: string
  label: string
  prefix: PrefixT
  componentId: ComponentIdT
  configPath: string
  staticConfig: ConfigSection[]
  statusEvent: string
  axisEvents: string[]
  // Non-axis telemetry events (read per-component in renderStatus). Optional;
  // most assemblies publish only status + axis events. CalibrationSourceStage
  // adds internalLightStatus here.
  extraEvents?: string[]
  renderCommands: (p: CommandsProps) => React.JSX.Element
  renderStatus: (p: StatusProps) => React.JSX.Element
}

const insertionStage: ComponentDescriptor = {
  key: IS_PREFIX_STR,
  label: 'Insertion Stage',
  prefix: IS_PREFIX,
  componentId: IS_COMPONENT_ID,
  configPath: IS_CONFIG_PATH,
  staticConfig: IS_CONFIG_VIEW,
  statusEvent: IS_STATUS_EVENT,
  axisEvents: [IS_AXIS_EVENT],
  renderCommands: (p) => (
    <InsertionStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <InsertionStageStatus status={p.status} axis={p.axes[IS_AXIS_EVENT] ?? {}} lifecycle={p.lifecycle} />
  )
}

const steeringBeamSplitter: ComponentDescriptor = {
  key: SBS_PREFIX_STR,
  label: 'Steering Beam Splitter',
  prefix: SBS_PREFIX,
  componentId: SBS_COMPONENT_ID,
  configPath: SBS_CONFIG_PATH,
  staticConfig: SBS_CONFIG_VIEW,
  statusEvent: SBS_STATUS_EVENT,
  axisEvents: [SBS_X_AXIS_EVENT, SBS_Y_AXIS_EVENT],
  renderCommands: (p) => (
    <SteeringBeamSplitterCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <SteeringBeamSplitterStatus
      status={p.status}
      xAxis={p.axes[SBS_X_AXIS_EVENT] ?? {}}
      yAxis={p.axes[SBS_Y_AXIS_EVENT] ?? {}}
      lifecycle={p.lifecycle}
    />
  )
}

const collimatorUnit: ComponentDescriptor = {
  key: CU_PREFIX_STR,
  label: 'Collimator Unit',
  prefix: CU_PREFIX,
  componentId: CU_COMPONENT_ID,
  configPath: CU_CONFIG_PATH,
  staticConfig: CU_CONFIG_VIEW,
  statusEvent: CU_STATUS_EVENT,
  axisEvents: [CU_FRONT_AXIS_EVENT, CU_REAR_AXIS_EVENT],
  renderCommands: (p) => (
    <CollimatorUnitCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <CollimatorUnitStatus
      status={p.status}
      frontAxis={p.axes[CU_FRONT_AXIS_EVENT] ?? {}}
      rearAxis={p.axes[CU_REAR_AXIS_EVENT] ?? {}}
      lifecycle={p.lifecycle}
    />
  )
}

const calibrationSourceStage: ComponentDescriptor = {
  key: CSS_PREFIX_STR,
  label: 'Calibration Source Stage',
  prefix: CSS_PREFIX,
  componentId: CSS_COMPONENT_ID,
  configPath: CSS_CONFIG_PATH,
  staticConfig: CSS_CONFIG_VIEW,
  statusEvent: CSS_STATUS_EVENT,
  axisEvents: [CSS_AXIS_EVENT],
  extraEvents: [CSS_LIGHT_EVENT],
  renderCommands: (p) => (
    <CalibrationSourceStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => {
    const lightEvent = p.extras[CSS_LIGHT_EVENT]
    return (
      <CalibrationSourceStageStatus
        status={p.status}
        axis={p.axes[CSS_AXIS_EVENT] ?? {}}
        light={lightEvent ? cssReadLight(lightEvent) : {}}
        lifecycle={p.lifecycle}
      />
    )
  }
}

const pshFocusStage: ComponentDescriptor = {
  key: PSHFS_PREFIX_STR,
  label: 'PSH Focus Stage',
  prefix: PSHFS_PREFIX,
  componentId: PSHFS_COMPONENT_ID,
  configPath: PSHFS_CONFIG_PATH,
  staticConfig: PSHFS_CONFIG_VIEW,
  statusEvent: PSHFS_STATUS_EVENT,
  axisEvents: [PSHFS_AXIS_EVENT],
  renderCommands: (p) => (
    <PshFocusStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <PshFocusStageStatus status={p.status} axis={p.axes[PSHFS_AXIS_EVENT] ?? {}} lifecycle={p.lifecycle} />
  )
}

const pitFocusStage: ComponentDescriptor = {
  key: PITFS_PREFIX_STR,
  label: 'PIT Focus Stage',
  prefix: PITFS_PREFIX,
  componentId: PITFS_COMPONENT_ID,
  configPath: PITFS_CONFIG_PATH,
  staticConfig: PITFS_CONFIG_VIEW,
  statusEvent: PITFS_STATUS_EVENT,
  axisEvents: [PITFS_AXIS_EVENT],
  renderCommands: (p) => (
    <PitFocusStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <PitFocusStageStatus status={p.status} axis={p.axes[PITFS_AXIS_EVENT] ?? {}} lifecycle={p.lifecycle} />
  )
}

const aptFocusStage: ComponentDescriptor = {
  key: APTFS_PREFIX_STR,
  label: 'APT Focus Stage',
  prefix: APTFS_PREFIX,
  componentId: APTFS_COMPONENT_ID,
  configPath: APTFS_CONFIG_PATH,
  staticConfig: APTFS_CONFIG_VIEW,
  statusEvent: APTFS_STATUS_EVENT,
  axisEvents: [APTFS_AXIS_EVENT],
  renderCommands: (p) => (
    <AptFocusStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <AptFocusStageStatus status={p.status} axis={p.axes[APTFS_AXIS_EVENT] ?? {}} lifecycle={p.lifecycle} />
  )
}

const tiltPlate: ComponentDescriptor = {
  key: TP_PREFIX_STR,
  label: 'Tilt Plate',
  prefix: TP_PREFIX,
  componentId: TP_COMPONENT_ID,
  configPath: TP_CONFIG_PATH,
  staticConfig: TP_CONFIG_VIEW,
  statusEvent: TP_STATUS_EVENT,
  axisEvents: [TP_X_AXIS_EVENT, TP_Y_AXIS_EVENT],
  renderCommands: (p) => (
    <TiltPlateCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <TiltPlateStatus
      status={p.status}
      xAxis={p.axes[TP_X_AXIS_EVENT] ?? {}}
      yAxis={p.axes[TP_Y_AXIS_EVENT] ?? {}}
      lifecycle={p.lifecycle}
    />
  )
}

const fiberSourceStage: ComponentDescriptor = {
  key: FSS_PREFIX_STR,
  label: 'Fiber Source Stage',
  prefix: FSS_PREFIX,
  componentId: FSS_COMPONENT_ID,
  configPath: FSS_CONFIG_PATH,
  staticConfig: FSS_CONFIG_VIEW,
  statusEvent: FSS_STATUS_EVENT,
  axisEvents: [FSS_X_AXIS_EVENT, FSS_Y_AXIS_EVENT, FSS_Z_AXIS_EVENT],
  extraEvents: [FSS_LIGHT_EVENT],
  renderCommands: (p) => (
    <FiberSourceStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => {
    const lightEvent = p.extras[FSS_LIGHT_EVENT]
    return (
      <FiberSourceStageStatus
        status={p.status}
        xAxis={p.axes[FSS_X_AXIS_EVENT] ?? {}}
        yAxis={p.axes[FSS_Y_AXIS_EVENT] ?? {}}
        zAxis={p.axes[FSS_Z_AXIS_EVENT] ?? {}}
        light={lightEvent ? fssReadLight(lightEvent) : {}}
        lifecycle={p.lifecycle}
      />
    )
  }
}

const pupilMaskStage: ComponentDescriptor = {
  key: PMS_PREFIX_STR,
  label: 'Pupil Mask Stage',
  prefix: PMS_PREFIX,
  componentId: PMS_COMPONENT_ID,
  configPath: PMS_CONFIG_PATH,
  staticConfig: PMS_CONFIG_VIEW,
  statusEvent: PMS_STATUS_EVENT,
  axisEvents: [PMS_X_AXIS_EVENT, PMS_Y_AXIS_EVENT, PMS_PHI_AXIS_EVENT],
  renderCommands: (p) => (
    <PupilMaskStageCommands status={p.status} ready={p.ready} busy={p.busy} run={p.run} />
  ),
  renderStatus: (p) => (
    <PupilMaskStageStatus
      status={p.status}
      xAxis={p.axes[PMS_X_AXIS_EVENT] ?? {}}
      yAxis={p.axes[PMS_Y_AXIS_EVENT] ?? {}}
      phiAxis={p.axes[PMS_PHI_AXIS_EVENT] ?? {}}
      lifecycle={p.lifecycle}
    />
  )
}

export const DESCRIPTORS: ComponentDescriptor[] = [
  insertionStage,
  steeringBeamSplitter,
  collimatorUnit,
  calibrationSourceStage,
  pshFocusStage,
  pitFocusStage,
  aptFocusStage,
  tiltPlate,
  fiberSourceStage,
  pupilMaskStage
]

export const REGISTRY: Record<string, ComponentDescriptor> = Object.fromEntries(
  DESCRIPTORS.map((d) => [d.key, d])
)

export const isRegistered = (key: string): boolean => key in REGISTRY

export const DEFAULT_KEY = IS_PREFIX_STR

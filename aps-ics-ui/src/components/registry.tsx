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
import type { Setup, SupervisorLifecycleState } from '@tmtsoftware/esw-ts'
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

export const DESCRIPTORS: ComponentDescriptor[] = [insertionStage, steeringBeamSplitter]

export const REGISTRY: Record<string, ComponentDescriptor> = Object.fromEntries(
  DESCRIPTORS.map((d) => [d.key, d])
)

export const isRegistered = (key: string): boolean => key in REGISTRY

export const DEFAULT_KEY = IS_PREFIX_STR

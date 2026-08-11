/*
 * InsertionStage Status section (SDD §4.3). Pure display of the assembly's
 * PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) and the single `axisStatus` event (axisState / position /
 * velocity / indexed / inPosition).
 *
 * Layout (status kit): the assembly-state chips in a top row, the single stage
 * axis in an AxisMatrix (axes as columns) below at a capped width, and a muted
 * MetaFooter (config-derived HCD label + CSW lifecycle). State colouring is
 * shared via ./statusBits (colorFor).
 */
import { Space, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { IS_HCD_LABEL } from '../models/insertionStage'
import type { AxisSnapshot, StatusSnapshot } from '../models/insertionStage'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

export const InsertionStageStatus = ({
  status,
  axis,
  lifecycle
}: {
  status: StatusSnapshot
  axis: AxisSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={10} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <AssemblyStateStrip status={status} />
    <div style={{ maxWidth: 360 }}>
      <AxisMatrix axes={[{ name: 'Stage', unit: 'mm', axis }]} />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{IS_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

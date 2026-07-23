/*
 * SteeringBeamSplitterStage Status section (SDD §4.3). Pure display of the
 * assembly's PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) plus TWO axis-status events — `xAxisStatus` and `yAxisStatus` —
 * each carrying axisState / position / velocity / indexed / inPosition.
 *
 * Layout (status kit): assembly-state chips on top, the two axes as columns in an
 * AxisMatrix at a capped width, and a muted MetaFooter (config-derived HCD label
 * + CSW lifecycle). State colouring is shared via ./statusBits (colorFor).
 */
import { Space, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { SBS_HCD_LABEL } from '../models/steeringBeamSplitter'
import type { AxisSnapshot, StatusSnapshot } from '../models/steeringBeamSplitter'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

export const SteeringBeamSplitterStatus = ({
  status,
  xAxis,
  yAxis,
  lifecycle
}: {
  status: StatusSnapshot
  xAxis: AxisSnapshot
  yAxis: AxisSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={10} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <AssemblyStateStrip status={status} />
    <div style={{ maxWidth: 460 }}>
      <AxisMatrix
        axes={[
          { name: 'X stage', unit: 'mm', axis: xAxis },
          { name: 'Y stage', unit: 'mm', axis: yAxis }
        ]}
      />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{SBS_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

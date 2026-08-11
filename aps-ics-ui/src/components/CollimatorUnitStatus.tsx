/*
 * CollimatorUnit Status section (SDD §4.3). Pure display of the assembly's
 * PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) plus TWO axis-status events — `frontAxisStatus` and
 * `rearAxisStatus` — each carrying axisState / position / velocity / indexed /
 * inPosition.
 *
 * Layout (status kit): assembly-state chips on top, the two axes as columns in an
 * AxisMatrix at a capped width, and a muted MetaFooter (config-derived HCD label
 * + CSW lifecycle). State colouring is shared via ./statusBits (colorFor).
 */
import { Space, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { CU_HCD_LABEL } from '../models/collimatorUnit'
import type { AxisSnapshot, StatusSnapshot } from '../models/collimatorUnit'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

export const CollimatorUnitStatus = ({
  status,
  frontAxis,
  rearAxis,
  lifecycle
}: {
  status: StatusSnapshot
  frontAxis: AxisSnapshot
  rearAxis: AxisSnapshot
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
          { name: 'Front axis', unit: 'mm', axis: frontAxis },
          { name: 'Rear axis', unit: 'mm', axis: rearAxis }
        ]}
      />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{CU_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

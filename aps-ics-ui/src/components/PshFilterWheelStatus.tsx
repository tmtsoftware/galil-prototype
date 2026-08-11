/*
 * PshFilterWheel Status section (SDD §4.3). Pure display of the assembly's PUBLISHED
 * telemetry: the `status` event (assemblyState / hcdState / commandState) and
 * the single `axisStatus` event (axisState / position° / velocity / indexed /
 * inPosition / wheelPositionNum).
 *
 * Layout (status kit): the assembly-state chips (AssemblyStateStrip), the single
 * filter-wheel axis as an AxisMatrix (State / Position / Velocity / Indexed / In
 * position) at a capped width, then the extra embedded-reported achieved slot
 * (Wheel position; -1 or absent = unknown, typically mid-select) as a compact
 * Descriptions block, and a muted MetaFooter (config-derived HCD label + CSW
 * lifecycle). State colouring is shared via ./statusBits (through the kit).
 */
import { Space, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { PSHFW_HCD_LABEL } from '../models/pshFilterWheel'
import type { AxisSnapshot, StatusSnapshot } from '../models/pshFilterWheel'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const slotText = (n?: number): string =>
  n === undefined || n < 0 ? '—' : String(n)

export const PshFilterWheelStatus = ({
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
      <AxisMatrix axes={[{ name: 'Filter wheel', unit: 'deg', axis }]}
        extraRows={[{ label: 'Wheel position', cells: [slotText(axis.wheelPositionNum)] }]}
      />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{PSHFW_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

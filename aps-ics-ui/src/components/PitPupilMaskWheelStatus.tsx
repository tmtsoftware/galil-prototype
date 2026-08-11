/*
 * PitPupilMaskWheel Status section (SDD §4.3). Pure display of the assembly's
 * PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) and the single `axisStatus` event (axisState / position° /
 * velocity / indexed / inPosition / wheelPositionNum / detentState).
 *
 * Layout (status kit): the assembly-state chips in a top row, the wheel axis in
 * an AxisMatrix (State / Position / Velocity / Indexed / In position) at a capped
 * width, the embedded achieved slot (wheelPositionNum) + sensed detentState in a
 * compact extra Descriptions block, and a muted MetaFooter (config-derived HCD
 * label + CSW lifecycle). Wheel position is the embedded-reported achieved slot;
 * -1 (or absent) means unknown — typically mid-select before the slot is
 * confirmed. detentState is the sensed detent position decoded from the HCD
 * InputOutputState digital inputs; with the dummy/unwired sensor addresses it
 * reads OUT OF POSITION. State colouring is shared via ./statusBits (colorFor).
 */
import { Space, Tag, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { PITPMW_HCD_LABEL } from '../models/pitPupilMaskWheel'
import type { AxisSnapshot, StatusSnapshot } from '../models/pitPupilMaskWheel'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const slotText = (n?: number): string =>
  n === undefined || n < 0 ? '—' : String(n)

const detentColor = (s?: string): string =>
  s === 'EXTENDED' || s === 'RETRACTED'
    ? 'blue'
    : s === 'OUT OF POSITION'
      ? 'orange'
      : 'default'

export const PitPupilMaskWheelStatus = ({
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
      <AxisMatrix axes={[{ name: 'Wheel', unit: 'deg', axis }]}
        extraRows={[
          { label: 'Wheel position', cells: [slotText(axis.wheelPositionNum)] },
          { label: 'Detent state', cells: [<Tag key='detent' color={detentColor(axis.detentState)}>{axis.detentState ?? '—'}</Tag>] }
        ]}
      />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{PITPMW_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

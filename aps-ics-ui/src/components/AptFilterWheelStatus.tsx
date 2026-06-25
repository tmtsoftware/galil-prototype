/*
 * AptFilterWheel Status section (SDD §4.3). Pure display of the assembly's
 * PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) and the single `axisStatus` event (axisState / position° /
 * velocity / indexed / inPosition / wheelPositionNum).
 *
 * Wheel position is the embedded-reported achieved slot; -1 (or absent) means
 * unknown — typically mid-select before the slot is confirmed. HCD label is
 * config-derived (bound HCD, not live); Lifecycle (CSW) is the supervisor state
 * polled by Main. State colouring is shared via ./statusBits.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { BoolTag, colorFor, fmt } from './statusBits'
import { APTFW_HCD_LABEL } from '../models/aptFilterWheel'
import type { AxisSnapshot, StatusSnapshot } from '../models/aptFilterWheel'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const slotText = (n?: number): string =>
  n === undefined || n < 0 ? '—' : String(n)

export const AptFilterWheelStatus = ({
  status,
  axis,
  lifecycle
}: {
  status: StatusSnapshot
  axis: AxisSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={8} style={{ width: '100%' }}>
    <Typography.Text
      type='secondary'
      style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item label='Assembly state'>
        <Tag color={colorFor(status.assemblyState)}>
          {status.assemblyState ?? '—'}
        </Tag>
      </Descriptions.Item>
      <Descriptions.Item label='HCD state'>
        <Tag color={colorFor(status.hcdState)}>{status.hcdState ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Command state'>
        <Tag color={colorFor(status.commandState)}>
          {status.commandState ?? '—'}
        </Tag>
      </Descriptions.Item>
    </Descriptions>

    <Descriptions title='Filter wheel axis' column={1} size='small' bordered>
      <Descriptions.Item label='Axis state'>
        <Tag color={colorFor(axis.axisState)}>{axis.axisState ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Wheel position'>
        {slotText(axis.wheelPositionNum)}
      </Descriptions.Item>
      <Descriptions.Item label='Position (deg)'>
        {fmt(axis.position)}
      </Descriptions.Item>
      <Descriptions.Item label='Velocity (deg/s)'>
        {fmt(axis.velocity)}
      </Descriptions.Item>
      <Descriptions.Item label='Indexed'>
        <BoolTag b={axis.indexed} />
      </Descriptions.Item>
      <Descriptions.Item label='In position'>
        <BoolTag b={axis.inPosition} />
      </Descriptions.Item>
    </Descriptions>

    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item
        label={
          <span>
            HCD{' '}
            <Typography.Text type='secondary' style={{ fontSize: 11 }}>
              (config)
            </Typography.Text>
          </span>
        }>
        {APTFW_HCD_LABEL}
      </Descriptions.Item>
      <Descriptions.Item
        label={
          <span>
            Lifecycle{' '}
            <Typography.Text type='secondary' style={{ fontSize: 11 }}>
              (CSW)
            </Typography.Text>
          </span>
        }>
        {lifecycle ?? '—'}
      </Descriptions.Item>
    </Descriptions>
  </Space>
)

/*
 * Assembly Status section (SDD §4.3). Pure display of the assembly's PUBLISHED
 * telemetry: the `status` event (assemblyState / hcdState / commandState) and the
 * `axisStatus` event (axisState / position / velocity / indexed / inPosition).
 *
 * Two non-telemetry rows are shown but clearly marked:
 *   - HCD label — config-derived (the bound HCD, not a live value).
 *   - Lifecycle (CSW) — the supervisor lifecycle state from AdminService, polled
 *     by Main; '—' if the admin route is unavailable.
 * State colouring is shared via ./statusBits so every stage panel matches.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { BoolTag, colorFor, fmt } from './statusBits'
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
  <Space direction='vertical' size={8} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item label='Assembly state'>
        <Tag color={colorFor(status.assemblyState)}>{status.assemblyState ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='HCD state'>
        <Tag color={colorFor(status.hcdState)}>{status.hcdState ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Command state'>
        <Tag color={colorFor(status.commandState)}>{status.commandState ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Axis state'>
        <Tag color={colorFor(axis.axisState)}>{axis.axisState ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Position (mm)'>{fmt(axis.position)}</Descriptions.Item>
      <Descriptions.Item label='Velocity'>{fmt(axis.velocity)}</Descriptions.Item>
      <Descriptions.Item label='Indexed'>
        <BoolTag b={axis.indexed} />
      </Descriptions.Item>
      <Descriptions.Item label='In position'>
        <BoolTag b={axis.inPosition} />
      </Descriptions.Item>
      <Descriptions.Item label={<span>HCD <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></span>}>
        {IS_HCD_LABEL}
      </Descriptions.Item>
      <Descriptions.Item label={<span>Lifecycle <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></span>}>
        {lifecycle ?? '—'}
      </Descriptions.Item>
    </Descriptions>
  </Space>
)

/*
 * FocKMirror Status section (SDD §4.3). Pure display of the assembly's PUBLISHED
 * telemetry: the `status` event (assemblyState / hcdState / commandState + the
 * K-Mirror operating mode / slewModeState / trackingModeState, SDD §8.6.5) and the
 * single `axisStatus` event (axisState / position° / velocity / indexed /
 * inPosition).
 *
 * Phase 1 is MANUAL only, so slewModeState reads NOT_SLEWING and trackingModeState
 * NOT_TRACKING; the rows are present so the tracking-phase telemetry surfaces are
 * already wired. HCD label is config-derived (bound HCD, not live); Lifecycle (CSW)
 * is the supervisor state polled by Main. State colouring is shared via ./statusBits.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { BoolTag, colorFor, fmt } from './statusBits'
import { KM_HCD_LABEL } from '../models/focKMirror'
import type { AxisSnapshot, StatusSnapshot } from '../models/focKMirror'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const modeColor = (s?: string): string =>
  s === 'TRACKING' ? 'green' : s === 'SLEWING' ? 'blue' : 'default'

export const FocKMirrorStatus = ({
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

    <Descriptions title='Operating mode' column={1} size='small' bordered>
      <Descriptions.Item label='Mode'>
        <Tag color={modeColor(status.mode)}>{status.mode ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Slew mode state'>
        {status.slewModeState ?? '—'}
      </Descriptions.Item>
      <Descriptions.Item label='Tracking mode state'>
        {status.trackingModeState ?? '—'}
      </Descriptions.Item>
    </Descriptions>

    <Descriptions title='K-Mirror axis' column={1} size='small' bordered>
      <Descriptions.Item label='Axis state'>
        <Tag color={colorFor(axis.axisState)}>{axis.axisState ?? '—'}</Tag>
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
        {KM_HCD_LABEL}
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

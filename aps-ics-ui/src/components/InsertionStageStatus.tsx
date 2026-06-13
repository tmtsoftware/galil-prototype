/*
 * InsertionStage status panel — pure display of the latest `status` and
 * `axisStatus` telemetry. State machines as colour-coded tags; axis readout as a
 * description list. Driven by snapshots Main keeps from the event subscription.
 */
import { Card, Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import type { AxisSnapshot, StatusSnapshot } from '../models/insertionStage'

const colorFor = (v?: string): string => {
  switch (v) {
    case 'OPERATIONAL':
    case 'READY':
    case 'IDLE':
      return 'green'
    case 'PRE_HOMED':
    case 'UNINITIALIZED':
    case 'HOMING':
    case 'MOVING':
      return 'blue'
    case 'PROCESSING':
    case 'ERROR_RECOVERY':
    case 'DEGRADED':
      return 'orange'
    case 'FAULTED':
    case 'FAILED':
    case 'ERROR':
    case 'LOST':
      return 'red'
    default:
      return 'default'
  }
}

const StateTag = ({ label, value }: { label: string; value?: string }): React.JSX.Element => (
  <Space>
    <Typography.Text type='secondary'>{label}</Typography.Text>
    <Tag color={colorFor(value)}>{value ?? '—'}</Tag>
  </Space>
)

const fmt = (n?: number, d = 3): string =>
  n === undefined || Number.isNaN(n) ? '—' : n.toFixed(d)

const BoolTag = ({ b }: { b?: boolean }): React.JSX.Element =>
  b === undefined ? <Tag>—</Tag> : <Tag color={b ? 'green' : 'default'}>{String(b)}</Tag>

export const InsertionStageStatus = ({
  status,
  axis
}: {
  status: StatusSnapshot
  axis: AxisSnapshot
}): React.JSX.Element => (
  <Card title='InsertionStage — Status' style={{ width: '28rem' }}>
    <Space direction='vertical' size='middle' style={{ width: '100%' }}>
      <Space size='large' wrap>
        <StateTag label='assembly' value={status.assemblyState} />
        <StateTag label='hcd' value={status.hcdState} />
        <StateTag label='command' value={status.commandState} />
      </Space>
      <Descriptions column={1} size='small' bordered>
        <Descriptions.Item label='axisState'>
          <Tag color={colorFor(axis.axisState)}>{axis.axisState ?? '—'}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label='position (mm)'>{fmt(axis.position)}</Descriptions.Item>
        <Descriptions.Item label='velocity'>{fmt(axis.velocity)}</Descriptions.Item>
        <Descriptions.Item label='indexed'>
          <BoolTag b={axis.indexed} />
        </Descriptions.Item>
        <Descriptions.Item label='inPosition'>
          <BoolTag b={axis.inPosition} />
        </Descriptions.Item>
      </Descriptions>
    </Space>
  </Card>
)
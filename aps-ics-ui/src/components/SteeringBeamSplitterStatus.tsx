/*
 * SteeringBeamSplitterStage Status section (SDD §4.3). Pure display of the
 * assembly's PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) plus TWO axis-status events — `xAxisStatus` and `yAxisStatus` —
 * each carrying axisState / position / velocity / indexed / inPosition.
 *
 * HCD label is config-derived (bound HCD, not live); Lifecycle (CSW) is the
 * supervisor state polled by Main. State colouring is shared via ./statusBits.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { BoolTag, colorFor, fmt } from './statusBits'
import { SBS_HCD_LABEL } from '../models/steeringBeamSplitter'
import type { AxisSnapshot, StatusSnapshot } from '../models/steeringBeamSplitter'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const AxisBlock = ({ title, axis }: { title: string; axis: AxisSnapshot }): React.JSX.Element => (
  <Descriptions title={title} column={1} size='small' bordered>
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
  </Descriptions>
)

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
    </Descriptions>

    <AxisBlock title='X stage' axis={xAxis} />
    <AxisBlock title='Y stage' axis={yAxis} />

    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item label={<span>HCD <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></span>}>
        {SBS_HCD_LABEL}
      </Descriptions.Item>
      <Descriptions.Item label={<span>Lifecycle <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></span>}>
        {lifecycle ?? '—'}
      </Descriptions.Item>
    </Descriptions>
  </Space>
)

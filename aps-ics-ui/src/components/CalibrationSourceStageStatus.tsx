/*
 * CalibrationSourceStage Status section (SDD §4.3). Pure display of the
 * assembly's PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState), the single `axisStatus` event (axisState / position / velocity /
 * indexed / inPosition), and the `internalLightStatus` event (lightOn /
 * lightIntensity).
 *
 * The light block is clearly marked STUBBED: the assembly reflects COMMANDED
 * light state, not a controller-3 readback (RIO not wired this cut).
 *
 * HCD label is config-derived (bound HCD, not live); Lifecycle (CSW) is the
 * supervisor state polled by Main. State colouring is shared via ./statusBits.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { BoolTag, colorFor, fmt } from './statusBits'
import { CSS_HCD_LABEL } from '../models/calibrationSourceStage'
import type { AxisSnapshot, LightSnapshot, StatusSnapshot } from '../models/calibrationSourceStage'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

export const CalibrationSourceStageStatus = ({
  status,
  axis,
  light,
  lifecycle
}: {
  status: StatusSnapshot
  axis: AxisSnapshot
  light: LightSnapshot
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

    <Descriptions title='Stage axis' column={1} size='small' bordered>
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

    <Descriptions
      title={<span>Light source <Typography.Text type='secondary' style={{ fontSize: 11 }}>(STUB — commanded, not read back)</Typography.Text></span>}
      column={1}
      size='small'
      bordered>
      <Descriptions.Item label='Light on'>
        <Tag color={light.lightOn === 'ON' ? 'green' : 'default'}>{light.lightOn ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Intensity (% of max)'>{fmt(light.lightIntensity, 1)}</Descriptions.Item>
    </Descriptions>

    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item label={<span>HCD <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></span>}>
        {CSS_HCD_LABEL}
      </Descriptions.Item>
      <Descriptions.Item label={<span>Lifecycle <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></span>}>
        {lifecycle ?? '—'}
      </Descriptions.Item>
    </Descriptions>
  </Space>
)

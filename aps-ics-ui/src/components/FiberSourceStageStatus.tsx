/*
 * FiberSourceStage Status section (SDD §4.3). Pure display of the assembly's
 * PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState), THREE axis-status events — `xAxisStatus` / `yAxisStatus` /
 * `zAxisStatus` — each carrying axisState / position / velocity / indexed /
 * inPosition, and the `internalLightStatus` event (lightOn / lightIntensity).
 *
 * Layout (status kit): assembly-state chips (AssemblyStateStrip) on top, the
 * three axes in an AxisMatrix (axes as columns) at a capped width, the STUBBED
 * light block kept as a compact Descriptions, and a muted MetaFooter (config HCD
 * label + CSW lifecycle). The light block reflects COMMANDED light state, not a
 * controller-4 readback (RIO not wired this cut). State colouring via ./statusBits.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { fmt } from './statusBits'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { FSS_HCD_LABEL } from '../models/fiberSourceStage'
import type { AxisSnapshot, LightSnapshot, StatusSnapshot } from '../models/fiberSourceStage'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

export const FiberSourceStageStatus = ({
  status,
  xAxis,
  yAxis,
  zAxis,
  light,
  lifecycle
}: {
  status: StatusSnapshot
  xAxis: AxisSnapshot
  yAxis: AxisSnapshot
  zAxis: AxisSnapshot
  light: LightSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={10} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <AssemblyStateStrip status={status} />
    <div style={{ maxWidth: 520 }}>
      <AxisMatrix
        axes={[
          { name: 'X axis', unit: 'mm', axis: xAxis },
          { name: 'Y axis', unit: 'mm', axis: yAxis },
          { name: 'Z axis', unit: 'mm', axis: zAxis }
        ]}
      />
    </div>

    <Descriptions
      title={<span>Light source <Typography.Text type='secondary' style={{ fontSize: 11 }}>(STUB — commanded, not read back)</Typography.Text></span>}
      column={1}
      size='small'
      bordered
      style={{ maxWidth: 360 }}>
      <Descriptions.Item label='Light on'>
        <Tag color={light.lightOn === 'ON' ? 'green' : 'default'}>{light.lightOn ?? '—'}</Tag>
      </Descriptions.Item>
      <Descriptions.Item label='Intensity (% of max)'>{fmt(light.lightIntensity, 1)}</Descriptions.Item>
    </Descriptions>

    <MetaFooter
      items={[
        { label: 'HCD', value: <>{FSS_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: <>{lifecycle ?? '—'} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></> }
      ]}
    />
  </Space>
)

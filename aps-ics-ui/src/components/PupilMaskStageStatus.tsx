/*
 * PupilMaskStage Status section (SDD §4.3). Pure display of the assembly's
 * PUBLISHED telemetry: the `status` event (assemblyState / hcdState /
 * commandState) plus THREE axis-status events — `xAxisStatus` / `yAxisStatus`
 * (linear, mm) and `phiAxisStatus` (rotational, deg).
 *
 * Layout (status kit): the assembly-state chips in a top row, the three axes in
 * an AxisMatrix (axes as columns) below at a capped width so the table stays
 * comfortably sized, and a muted MetaFooter (config-derived HCD label + CSW
 * lifecycle). State colouring is shared via ./statusBits (colorFor).
 */
import { Space, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { PMS_HCD_LABEL } from '../models/pupilMaskStage'
import type { AxisSnapshot, StatusSnapshot } from '../models/pupilMaskStage'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

export const PupilMaskStageStatus = ({
  status,
  xAxis,
  yAxis,
  phiAxis,
  lifecycle
}: {
  status: StatusSnapshot
  xAxis: AxisSnapshot
  yAxis: AxisSnapshot
  phiAxis: AxisSnapshot
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
          { name: 'X stage', unit: 'mm', axis: xAxis },
          { name: 'Y stage', unit: 'mm', axis: yAxis },
          { name: 'Φ stage', unit: 'deg', axis: phiAxis }
        ]}
      />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{PMS_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

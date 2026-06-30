/*
 * PIT Detector Status section (SDD §5.2.3). status / temperatureStatus /
 * setupStatus (with ROI + integration time) / configStatus (Teledyne). Pure display.
 */
import { Descriptions, Space, Typography } from 'antd'
import React from 'react'
import { AssemblyBlock, SetupBlock, TeledyneConfigBlock, TemperatureBlock } from './detectorStatusBits'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'
import type { ConfigSnapshot, SetupSnapshot, StatusSnapshot, TemperatureSnapshot } from '../models/pitDetector'

export const PitDetectorStatus = ({
  status,
  temperature,
  setup,
  config,
  lifecycle
}: {
  status: StatusSnapshot
  temperature: TemperatureSnapshot
  setup: SetupSnapshot
  config: ConfigSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={8} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      DETECTOR STATUS
    </Typography.Text>
    <AssemblyBlock status={status} />
    <TemperatureBlock t={temperature} />
    <SetupBlock s={setup} showRoi />
    <TeledyneConfigBlock c={config} />
    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item label={<span>Lifecycle <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></span>}>
        {lifecycle ?? '—'}
      </Descriptions.Item>
    </Descriptions>
  </Space>
)

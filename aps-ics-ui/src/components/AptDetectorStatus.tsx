/*
 * APT Detector Status section (SDD §5.1.3). Displays the published telemetry:
 * status (assembly / camera / cooling), temperatureStatus, setupStatus,
 * configStatus and the APT-only guidingStatus. Pure display.
 */
import { Descriptions, Space, Typography } from 'antd'
import React from 'react'
import { AptConfigBlock, AssemblyBlock, GuidingBlock, SetupBlock, TemperatureBlock } from './detectorStatusBits'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'
import type {
  ConfigSnapshot,
  GuidingSnapshot,
  SetupSnapshot,
  StatusSnapshot,
  TemperatureSnapshot
} from '../models/aptDetector'

export const AptDetectorStatus = ({
  status,
  temperature,
  setup,
  config,
  guiding,
  lifecycle
}: {
  status: StatusSnapshot
  temperature: TemperatureSnapshot
  setup: SetupSnapshot
  config: ConfigSnapshot
  guiding: GuidingSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={8} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      DETECTOR STATUS
    </Typography.Text>
    <AssemblyBlock status={status} />
    <TemperatureBlock t={temperature} />
    <SetupBlock s={setup} />
    <AptConfigBlock c={config} />
    <GuidingBlock g={guiding} />
    <Descriptions column={1} size='small' bordered>
      <Descriptions.Item label={<span>Lifecycle <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></span>}>
        {lifecycle ?? '—'}
      </Descriptions.Item>
    </Descriptions>
  </Space>
)

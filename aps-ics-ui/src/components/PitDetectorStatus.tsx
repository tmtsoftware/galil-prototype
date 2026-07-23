/*
 * PIT Detector Status section (SDD §5.2.3). Pure display of published telemetry.
 *
 * Like APT, but the PIT assembly has NO guidingStatus event: its configuration
 * is drawn from setupStatus (ROI / binning / integration time / frame rate /
 * acquisition mode / buffer model) and configStatus (Teledyne analog gain / bit
 * depth / shutter mode / CMS). We present the assembly/camera/cooling state chips
 * and CCD temperature, then ONE clean detector-configuration table combining the
 * meaningful setup + config params.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { fmt } from './statusBits'
import { detColor } from './detectorStatusBits'
import { MetaFooter, StateChip, StateStrip } from './statusLayout'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'
import type { ConfigSnapshot, SetupSnapshot, StatusSnapshot, TemperatureSnapshot } from '../models/pitDetector'

const dash = (v: unknown): string => (v === undefined || v === null ? '—' : String(v))

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
}): React.JSX.Element => {
  const roi = `${dash(setup.roiWidth)} × ${dash(setup.roiHeight)} @ (${dash(setup.roiStartCol)}, ${dash(setup.roiStartRow)})`
  return (
    <Space direction='vertical' size={12} style={{ width: '100%' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        DETECTOR STATUS
      </Typography.Text>

      <StateStrip>
        <StateChip label='Assembly'><Tag color={detColor(status.assemblyState)}>{status.assemblyState ?? '—'}</Tag></StateChip>
        <StateChip label='Camera acq'><Tag color={detColor(status.cameraAcquisitionState)}>{status.cameraAcquisitionState ?? '—'}</Tag></StateChip>
        <StateChip label='Cooling'><Tag color={detColor(status.coolingHealth)}>{status.coolingHealth ?? '—'}</Tag></StateChip>
        <StateChip label='Camera'>
          {status.cameraPresent === undefined ? <Tag>—</Tag> : <Tag color={status.cameraPresent ? 'green' : 'red'}>{status.cameraPresent ? 'present' : 'absent'}</Tag>}
        </StateChip>
      </StateStrip>

      <div style={{ maxWidth: 420 }}>
        <Descriptions title='Temperature' column={{ xs: 1, sm: 2 }} size='small' bordered>
          <Descriptions.Item label='Detector (°C)'>{fmt(temperature.detectorTemperature, 1)}</Descriptions.Item>
          <Descriptions.Item label='Set point (°C)'>{fmt(temperature.temperatureSetPoint, 1)}</Descriptions.Item>
        </Descriptions>
      </div>

      <div>
        <Descriptions
          title='Detector configuration'
          column={{ xs: 1, sm: 2, xl: 3 }}
          size='small'
          bordered
          labelStyle={{ whiteSpace: 'nowrap' }}
          contentStyle={{ whiteSpace: 'nowrap' }}>
          <Descriptions.Item label='ROI (px)'>{roi}</Descriptions.Item>
          <Descriptions.Item label='Binning'>{`${dash(setup.hBin)} × ${dash(setup.vBin)}`}</Descriptions.Item>
          <Descriptions.Item label='Integration (s)'>{fmt(setup.integrationTime, 3)}</Descriptions.Item>
          <Descriptions.Item label='Frame rate (Hz)'>{fmt(setup.frameRate, 2)}</Descriptions.Item>
          <Descriptions.Item label='Acquisition mode'><Tag color={detColor(setup.acquisitionMode)}>{setup.acquisitionMode ?? '—'}</Tag></Descriptions.Item>
          <Descriptions.Item label='Analog gain'>{dash(config.analogGain)}</Descriptions.Item>
          <Descriptions.Item label='Bit depth'>{dash(config.bitDepth)}</Descriptions.Item>
          <Descriptions.Item label='Shutter mode'>{dash(config.shutterMode)}</Descriptions.Item>
          <Descriptions.Item label='CMS'>{dash(config.cms)}</Descriptions.Item>
          <Descriptions.Item label='Buffer model'>{dash(setup.bufferModel)}</Descriptions.Item>
        </Descriptions>
      </div>

      <MetaFooter
        items={[
          { label: 'Image size', value: `${dash(setup.imageSize)} bytes` },
          { label: 'Path', value: dash(setup.path) },
          { label: 'Lifecycle', value: lifecycle ?? '—' }
        ]}
      />
    </Space>
  )
}

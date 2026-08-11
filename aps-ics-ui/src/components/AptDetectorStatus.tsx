/*
 * APT Detector Status section (SDD §5.1). Pure display of published telemetry.
 *
 * The assembly publishes several telemetry events (status / temperatureStatus /
 * setupStatus / configStatus / guidingStatus), but those are transport groupings,
 * not user-facing ones: per SDD §5.1.2.3.1 `configureDetector(ROI, gain)` sets ROI,
 * binning and gain generally (the guidingStatus event merely REPORTS gain / ROI /
 * integration time — they are not guiding-specific). So we present ONE clean
 * detector-configuration table ordered like the SDD Assembly Configuration table
 * (Table 5-3), plus the assembly/camera/cooling state chips and the CCD
 * temperature. Values are drawn from whichever event carries them.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { fmt } from './statusBits'
import { detColor } from './detectorStatusBits'
import { MetaFooter, StateChip, StateStrip } from './statusLayout'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'
import type {
  ConfigSnapshot,
  GuidingSnapshot,
  SetupSnapshot,
  StatusSnapshot,
  TemperatureSnapshot
} from '../models/aptDetector'

const dash = (v: unknown): string => (v === undefined || v === null ? '—' : String(v))

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
}): React.JSX.Element => {
  const roi = `${dash(guiding.roiWidth)} × ${dash(guiding.roiHeight)} @ (${dash(guiding.roiStartCol)}, ${dash(guiding.roiStartRow)})`
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
          <Descriptions.Item label='Gain mode'>{dash(guiding.gainMode)}</Descriptions.Item>
          <Descriptions.Item label='ROI (px)'>{roi}</Descriptions.Item>
          <Descriptions.Item label='Binning'>{`${dash(setup.hBin)} × ${dash(setup.vBin)}`}</Descriptions.Item>
          <Descriptions.Item label='Integration (s)'>{fmt(guiding.integrationTime, 3)}</Descriptions.Item>
          <Descriptions.Item label='Frame rate (Hz)'>{fmt(setup.frameRate, 2)}</Descriptions.Item>
          <Descriptions.Item label='Acquisition mode'><Tag color={detColor(setup.acquisitionMode)}>{setup.acquisitionMode ?? '—'}</Tag></Descriptions.Item>
          <Descriptions.Item label='Pixel encoding'>{dash(config.pixelEncoding)}</Descriptions.Item>
          <Descriptions.Item label='Pixel readout rate'>{dash(config.pixelReadoutRate)}</Descriptions.Item>
          <Descriptions.Item label='Spurious noise filter'>{dash(config.spuriousNoiseFilter)}</Descriptions.Item>
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

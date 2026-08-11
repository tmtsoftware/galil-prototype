/*
 * Shared building blocks for the detector Status panels (SDD §5.x.3). The three
 * detectors publish the same status / temperatureStatus shapes and near-identical
 * setupStatus / configStatus, so the rendered blocks live here once. Colour
 * mapping extends the shared statusBits palette with the detector-only states
 * (camera acquisition + cooling health).
 */
import { Descriptions, Tag, Typography } from 'antd'
import React from 'react'
import { fmt } from './statusBits'
import type { StatusSnapshot } from '../models/detector'
import type {
  ConfigSnapshot,
  GuidingSnapshot,
  SetupSnapshot,
  TemperatureSnapshot
} from '../models/detector'

export const detColor = (v?: string): string => {
  switch (v) {
    case 'READY':
    case 'IDLE':
    case 'Good':
      return 'green'
    case 'BUSY':
    case 'STREAMING':
      return 'blue'
    case 'PAUSED':
    case 'RECOVERING':
    case 'DEGRADED':
    case 'Degraded':
      return 'orange'
    case 'FAULTED':
    case 'FAULT':
    case 'Bad':
      return 'red'
    default:
      return 'default'
  }
}

const tag = (v?: string): React.JSX.Element => <Tag color={detColor(v)}>{v ?? '—'}</Tag>

export const AssemblyBlock = ({ status }: { status: StatusSnapshot }): React.JSX.Element => (
  <Descriptions column={1} size='small' bordered>
    <Descriptions.Item label='Assembly state'>{tag(status.assemblyState)}</Descriptions.Item>
    <Descriptions.Item label='Camera acquisition'>{tag(status.cameraAcquisitionState)}</Descriptions.Item>
    <Descriptions.Item label='Cooling health'>{tag(status.coolingHealth)}</Descriptions.Item>
    <Descriptions.Item label='Camera present'>
      {status.cameraPresent === undefined ? <Tag>—</Tag> : <Tag color={status.cameraPresent ? 'green' : 'red'}>{String(status.cameraPresent)}</Tag>}
    </Descriptions.Item>
  </Descriptions>
)

export const TemperatureBlock = ({ t }: { t: TemperatureSnapshot }): React.JSX.Element => (
  <Descriptions title='Temperature' column={1} size='small' bordered>
    <Descriptions.Item label='Detector (degC)'>{fmt(t.detectorTemperature, 1)}</Descriptions.Item>
    <Descriptions.Item label='Set point (degC)'>{fmt(t.temperatureSetPoint, 1)}</Descriptions.Item>
  </Descriptions>
)

export const SetupBlock = ({
  s,
  showRoi
}: {
  s: SetupSnapshot
  showRoi?: boolean
}): React.JSX.Element => (
  <Descriptions title='Setup' column={1} size='small' bordered>
    <Descriptions.Item label='Image size (bytes)'>{s.imageSize ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='Acquisition mode'>{tag(s.acquisitionMode)}</Descriptions.Item>
    <Descriptions.Item label='Buffer model'>{tag(s.bufferModel)}</Descriptions.Item>
    <Descriptions.Item label='Frame rate (Hz)'>{fmt(s.frameRate, 2)}</Descriptions.Item>
    <Descriptions.Item label='Binning'>{(s.hBin ?? '—') + ' x ' + (s.vBin ?? '—')}</Descriptions.Item>
    {showRoi && (
      <Descriptions.Item label='Integration time'>{fmt(s.integrationTime, 3)}</Descriptions.Item>
    )}
    {showRoi && (
      <Descriptions.Item label='ROI'>
        {(s.roiWidth ?? '—') + ' x ' + (s.roiHeight ?? '—') + ' @ (' + (s.roiStartCol ?? '—') + ', ' + (s.roiStartRow ?? '—') + ')'}
      </Descriptions.Item>
    )}
    <Descriptions.Item label='Path'>
      <Typography.Text style={{ fontSize: 12 }}>{s.path ?? '—'}</Typography.Text>
    </Descriptions.Item>
  </Descriptions>
)

export const AptConfigBlock = ({ c }: { c: ConfigSnapshot }): React.JSX.Element => (
  <Descriptions title='Config' column={1} size='small' bordered>
    <Descriptions.Item label='Pixel encoding'>{c.pixelEncoding ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='Pixel readout rate'>{c.pixelReadoutRate ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='Spurious noise filter'>
      {c.spuriousNoiseFilter === undefined ? '—' : String(c.spuriousNoiseFilter)}
    </Descriptions.Item>
  </Descriptions>
)

export const TeledyneConfigBlock = ({ c }: { c: ConfigSnapshot }): React.JSX.Element => (
  <Descriptions title='Config' column={1} size='small' bordered>
    <Descriptions.Item label='Analog gain'>{c.analogGain ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='Bit depth'>{c.bitDepth ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='Shutter mode'>{c.shutterMode ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='CMS'>{c.cms ?? '—'}</Descriptions.Item>
  </Descriptions>
)

export const GuidingBlock = ({ g }: { g: GuidingSnapshot }): React.JSX.Element => (
  <Descriptions title='Guiding' column={1} size='small' bordered>
    <Descriptions.Item label='Gain mode'>{g.gainMode ?? '—'}</Descriptions.Item>
    <Descriptions.Item label='Integration time'>{fmt(g.integrationTime, 3)}</Descriptions.Item>
    <Descriptions.Item label='ROI'>
      {(g.roiWidth ?? '—') + ' x ' + (g.roiHeight ?? '—') + ' @ (' + (g.roiStartCol ?? '—') + ', ' + (g.roiStartRow ?? '—') + ')'}
    </Descriptions.Item>
  </Descriptions>
)

/*
 * PSH Detector Commands section (SDD §5.3 "Command Section"). Gated by
 * `commandEnabled`; disabled while a command is in flight. Submit + logging in Main.
 * Note: takeExposure stores to APS Shared Disk; storeExposure archives to DMS;
 * there is NO takeAndStoreExposure. configureDetector carries procedureId/observationId.
 */
import { Button, Input, InputNumber, Select, Space, Switch, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import {
  ANALOG_GAINS,
  BIT_DEPTHS,
  CMS_MODES,
  RECOVER_MODES,
  SHUTTER_MODES,
  abortExposureCmd,
  commandEnabled,
  configureDetectorCmd,
  configureDetectorCoolingCmd,
  recoverCmd,
  resetCameraCmd,
  setDefaultConfigurationCmd,
  storeExposureCmd,
  takeExposureCmd
} from '../models/pshDetector'
import type { AnalogGain, BitDepth, CmdName, CmsMode, RecoverMode, ShutterMode, StatusSnapshot } from '../models/pshDetector'

const { Text } = Typography

const Row = ({
  label,
  danger,
  enabled,
  onSubmit,
  children
}: {
  label: string
  danger?: boolean
  enabled: boolean
  onSubmit: () => void
  children?: React.ReactNode
}): React.JSX.Element => (
  <div
    style={{
      display: 'grid',
      gridTemplateColumns: 'max-content 1fr auto',
      alignItems: 'center',
      gap: 8,
      padding: '8px 10px',
      border: '1px solid rgba(0,0,0,0.08)',
      borderRadius: 8
    }}>
    <Text>{label}</Text>
    <div style={{ display: 'flex', gap: 6, alignItems: 'center', justifyContent: 'flex-end', flexWrap: 'wrap', minWidth: 0 }}>
      {children}
    </div>
    <Button size='small' danger={danger} disabled={!enabled} onClick={onSubmit}>
      Submit
    </Button>
  </div>
)

const num = (v: number, set: (n: number) => void, suffix?: string, step = 1, width = 84): React.JSX.Element => (
  <InputNumber size='small' value={v} onChange={(x) => set(Number(x ?? 0))} step={step} suffix={suffix} style={{ width }} />
)

export const PshDetectorCommands = ({
  status,
  ready,
  busy,
  run
}: {
  status: StatusSnapshot
  ready: boolean
  busy: boolean
  run: (setup: Setup, label: string) => void
}): React.JSX.Element => {
  const [setPoint, setSetPoint] = useState(-30)
  const [gain, setGain] = useState<AnalogGain>('HIGH')
  const [bitDepth, setBitDepth] = useState<BitDepth>('16-bit')
  const [shutter, setShutter] = useState<ShutterMode>('ROLLING')
  const [cms, setCms] = useState<CmsMode>('OFF')
  const [startRow, setStartRow] = useState(0)
  const [startCol, setStartCol] = useState(0)
  const [width, setWidth] = useState(256)
  const [height, setHeight] = useState(256)
  const [hBin, setHBin] = useState(1)
  const [vBin, setVBin] = useState(1)
  const [procId, setProcId] = useState('PROC-1')
  const [obsId, setObsId] = useState('OBS-1')
  const [it, setIt] = useState(1.0)
  const [recMode, setRecMode] = useState<RecoverMode>('CLEAR')
  const [autoResume, setAutoResume] = useState(false)

  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)
  const gainSelect = (
    <Select<AnalogGain> size='small' value={gain} onChange={setGain} style={{ width: 90 }} options={ANALOG_GAINS.map((g) => ({ value: g, label: g }))} />
  )

  return (
    <Space direction='vertical' size={6} style={{ width: '100%' }}>
      <Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        DETECTOR COMMANDS
      </Text>

      <Row
        label='Configure cooling'
        enabled={on('configureDetectorCooling')}
        onSubmit={() => run(configureDetectorCoolingCmd(setPoint), `configureDetectorCooling [setPoint=${setPoint}]`)}>
        {num(setPoint, setSetPoint, '°C', 1, 92)}
      </Row>

      <Row
        label='Configure detector'
        enabled={on('configureDetector')}
        onSubmit={() =>
          run(
            configureDetectorCmd(startRow, startCol, width, height, hBin, vBin, procId, obsId, gain, bitDepth, shutter, cms),
            `configureDetector [roi=${width}x${height}@(${startCol},${startRow}), proc=${procId}, obs=${obsId}, gain=${gain}, bit=${bitDepth}, shutter=${shutter}, cms=${cms}]`
          )
        }>
        {num(startRow, setStartRow, 'r', 1, 60)}
        {num(startCol, setStartCol, 'c', 1, 60)}
        {num(width, setWidth, 'w', 1, 68)}
        {num(height, setHeight, 'h', 1, 68)}
        {num(hBin, setHBin, 'hB', 1, 56)}
        {num(vBin, setVBin, 'vB', 1, 56)}
        <Input size='small' value={procId} onChange={(e) => setProcId(e.target.value)} style={{ width: 96 }} placeholder='procedureId' />
        <Input size='small' value={obsId} onChange={(e) => setObsId(e.target.value)} style={{ width: 96 }} placeholder='observationId' />
        {gainSelect}
        <Select<BitDepth> size='small' value={bitDepth} onChange={setBitDepth} style={{ width: 88 }} options={BIT_DEPTHS.map((b) => ({ value: b, label: b }))} />
        <Select<ShutterMode> size='small' value={shutter} onChange={setShutter} style={{ width: 96 }} options={SHUTTER_MODES.map((sm) => ({ value: sm, label: sm }))} />
        <Select<CmsMode> size='small' value={cms} onChange={setCms} style={{ width: 76 }} options={CMS_MODES.map((c) => ({ value: c, label: c }))} />
      </Row>

      <Row label='Set default configuration' enabled={on('setDefaultConfiguration')} onSubmit={() => run(setDefaultConfigurationCmd(), 'setDefaultConfiguration')} />

      <Row
        label='Take exposure (→ shared disk)'
        enabled={on('takeExposure')}
        onSubmit={() => run(takeExposureCmd(it, gain), `takeExposure [it=${it}s, gain=${gain}]`)}>
        {num(it, setIt, 's', 0.1, 84)}
        {gainSelect}
      </Row>

      <Row label='Store exposure (→ DMS)' enabled={on('storeExposure')} onSubmit={() => run(storeExposureCmd(), 'storeExposure')} />
      <Row label='Abort exposure' danger enabled={on('abortExposure')} onSubmit={() => run(abortExposureCmd(), 'abortExposure')} />

      <Row
        label='Recover'
        danger
        enabled={on('recover')}
        onSubmit={() => run(recoverCmd(recMode, autoResume), `recover [mode=${recMode}, autoResume=${autoResume}]`)}>
        <Select<RecoverMode> size='small' value={recMode} onChange={setRecMode} style={{ width: 96 }} options={RECOVER_MODES.map((m) => ({ value: m, label: m }))} />
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Text style={{ fontSize: 12 }}>auto</Text>
          <Switch size='small' checked={autoResume} onChange={setAutoResume} />
        </span>
      </Row>

      <Row label='Reset camera' danger enabled={on('resetCamera')} onSubmit={() => run(resetCameraCmd(), 'resetCamera')} />
    </Space>
  )
}

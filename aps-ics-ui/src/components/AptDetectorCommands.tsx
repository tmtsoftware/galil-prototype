/*
 * APT Detector Commands section (SDD §5.1 "Command Section"). One row per command
 * with inline parameters and its own Submit button, gated by `commandEnabled`
 * (mirrors the assembly validate gate); everything is disabled while a command is
 * in flight (`busy`). Submit + result logging live in Main via `run`.
 */
import { Button, InputNumber, Select, Space, Switch, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import {
  FAN_SPEEDS,
  GAIN_MODES,
  RECOVER_MODES,
  abortHighSpeedExposureCmd,
  commandEnabled,
  configDetectorCmd,
  configDetectorCoolingCmd,
  pauseExposureLoopCmd,
  recoverCmd,
  resetCameraCmd,
  restartExposureLoopCmd,
  setDefaultConfigurationCmd,
  startExposureLoopCmd,
  stopExposureLoopCmd,
  takeAndPublishExposureCmd,
  takeAndStoreExposureCmd,
  takeHighSpeedExposuresCmd
} from '../models/aptDetector'
import type { CmdName, FanSpeed, GainMode, RecoverMode, StatusSnapshot } from '../models/aptDetector'

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

export const AptDetectorCommands = ({
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
  const [setPoint, setSetPoint] = useState(-40)
  const [fan, setFan] = useState<FanSpeed>('MEDIUM')
  const [gain, setGain] = useState<GainMode>('12-BIT')
  const [startRow, setStartRow] = useState(0)
  const [startCol, setStartCol] = useState(0)
  const [width, setWidth] = useState(256)
  const [height, setHeight] = useState(256)
  const [hBin, setHBin] = useState(1)
  const [vBin, setVBin] = useState(1)
  const [it, setIt] = useState(0.5)
  const [storeIt, setStoreIt] = useState(0.5)
  const [loopIt, setLoopIt] = useState(0.1)
  const [rate, setRate] = useState(10)
  const [hsIt, setHsIt] = useState(0.01)
  const [hsRoi, setHsRoi] = useState(1)
  const [hsFrameRate, setHsFrameRate] = useState(100)
  const [hsDuration, setHsDuration] = useState(2)
  const [recMode, setRecMode] = useState<RecoverMode>('CLEAR')
  const [autoResume, setAutoResume] = useState(false)

  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)
  const gainSelect = (v: GainMode, set: (g: GainMode) => void): React.JSX.Element => (
    <Select<GainMode> size='small' value={v} onChange={set} style={{ width: 96 }} options={GAIN_MODES.map((g) => ({ value: g, label: g }))} />
  )

  return (
    <Space direction='vertical' size={6} style={{ width: '100%' }}>
      <Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        DETECTOR COMMANDS
      </Text>

      <Row
        label='Configure cooling'
        enabled={on('configDetectorCooling')}
        onSubmit={() => run(configDetectorCoolingCmd(setPoint, fan), `configDetectorCooling [setPoint=${setPoint}, fan=${fan}]`)}>
        {num(setPoint, setSetPoint, '°C', 1, 92)}
        <Select<FanSpeed> size='small' value={fan} onChange={setFan} style={{ width: 96 }} options={FAN_SPEEDS.map((f) => ({ value: f, label: f }))} />
      </Row>

      <Row
        label='Configure detector'
        enabled={on('configDetector')}
        onSubmit={() =>
          run(
            configDetectorCmd(startRow, startCol, width, height, hBin, vBin, gain),
            `configDetector [roi=${width}x${height}@(${startCol},${startRow}), bin=${hBin}x${vBin}, gain=${gain}]`
          )
        }>
        {num(startRow, setStartRow, 'r', 1, 70)}
        {num(startCol, setStartCol, 'c', 1, 70)}
        {num(width, setWidth, 'w', 1, 76)}
        {num(height, setHeight, 'h', 1, 76)}
        {num(hBin, setHBin, 'hB', 1, 64)}
        {num(vBin, setVBin, 'vB', 1, 64)}
        {gainSelect(gain, setGain)}
      </Row>

      <Row label='Set default configuration' enabled={on('setDefaultConfiguration')} onSubmit={() => run(setDefaultConfigurationCmd(), 'setDefaultConfiguration')} />

      <Row
        label='Take & publish exposure'
        enabled={on('takeAndPublishExposure')}
        onSubmit={() => run(takeAndPublishExposureCmd(it, gain), `takeAndPublishExposure [it=${it}s, gain=${gain}]`)}>
        {num(it, setIt, 's', 0.1, 84)}
        {gainSelect(gain, setGain)}
      </Row>

      <Row
        label='Take & store exposure'
        enabled={on('takeAndStoreExposure')}
        onSubmit={() => run(takeAndStoreExposureCmd(storeIt, gain), `takeAndStoreExposure [it=${storeIt}s, gain=${gain}]`)}>
        {num(storeIt, setStoreIt, 's', 0.1, 84)}
        {gainSelect(gain, setGain)}
      </Row>

      <Row
        label='Start exposure loop'
        enabled={on('startExposureLoop')}
        onSubmit={() => run(startExposureLoopCmd(loopIt, rate, gain), `startExposureLoop [it=${loopIt}s, rate=${rate}Hz, gain=${gain}]`)}>
        {num(loopIt, setLoopIt, 's', 0.05, 84)}
        {num(rate, setRate, 'Hz', 1, 80)}
        {gainSelect(gain, setGain)}
      </Row>

      <Row label='Pause exposure loop' enabled={on('pauseExposureLoop')} onSubmit={() => run(pauseExposureLoopCmd(), 'pauseExposureLoop')} />
      <Row label='Restart exposure loop' enabled={on('restartExposureLoop')} onSubmit={() => run(restartExposureLoopCmd(), 'restartExposureLoop')} />
      <Row label='Stop exposure loop' danger enabled={on('stopExposureLoop')} onSubmit={() => run(stopExposureLoopCmd(), 'stopExposureLoop')} />

      <Row
        label='Take high-speed exposures'
        enabled={on('takeHighSpeedExposures')}
        onSubmit={() =>
          run(
            takeHighSpeedExposuresCmd(hsIt, gain, hsRoi, hsFrameRate, hsDuration),
            `takeHighSpeedExposures [it=${hsIt}s, roiId=${hsRoi}, frameRate=${hsFrameRate}, duration=${hsDuration}s]`
          )
        }>
        {num(hsIt, setHsIt, 's', 0.01, 80)}
        {num(hsRoi, setHsRoi, 'id', 1, 60)}
        {num(hsFrameRate, setHsFrameRate, 'fps', 10, 80)}
        {num(hsDuration, setHsDuration, 's', 1, 72)}
        {gainSelect(gain, setGain)}
      </Row>

      <Row label='Abort high-speed exposure' danger enabled={on('abortHighSpeedExposure')} onSubmit={() => run(abortHighSpeedExposureCmd(), 'abortHighSpeedExposure')} />

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

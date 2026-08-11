/*
 * APT Detector Commands section (SDD §5.1 "Command Section"). Commands grouped by
 * context (command kit): Cooling, Detector config, Single exposure, Exposure loop
 * (start + its pause/restart/stop), High-speed (take + its abort) and Recovery
 * (recover + reset camera). Gating is unchanged — `commandEnabled` mirrors the
 * assembly validate gate; everything is disabled while a command is in flight
 * (`busy`). Submit + result logging live in Main via `run`.
 */
import { InputNumber, Select, Space, Switch, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
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

  // Gain is shared across several exposure commands; a fresh Field each call.
  const gainField = (): React.JSX.Element => (
    <Field label='Gain'>
      <Select<GainMode> size='small' value={gain} onChange={setGain} options={GAIN_MODES.map((g) => ({ value: g, label: g }))} />
    </Field>
  )

  return (
    <Space direction='vertical' size={8} style={{ width: '100%' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        DETECTOR COMMANDS
      </Typography.Text>
      <CommandGroups>
        <CommandGroup title='Cooling'>
          <ParamCommand
            name='Configure cooling'
            enabled={on('configDetectorCooling')}
            onSubmit={() => run(configDetectorCoolingCmd(setPoint, fan), `configDetectorCooling [setPoint=${setPoint}, fan=${fan}]`)}>
            <Field label='Set point'>
              <InputNumber size='small' value={setPoint} onChange={(v) => setSetPoint(Number(v ?? 0))} step={1} suffix='°C' />
            </Field>
            <Field label='Fan speed'>
              <Select<FanSpeed> size='small' value={fan} onChange={setFan} options={FAN_SPEEDS.map((f) => ({ value: f, label: f }))} />
            </Field>
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Detector config'>
          <ParamCommand
            name='Configure detector'
            enabled={on('configDetector')}
            onSubmit={() =>
              run(
                configDetectorCmd(startRow, startCol, width, height, hBin, vBin, gain),
                `configDetector [roi=${width}x${height}@(${startCol},${startRow}), bin=${hBin}x${vBin}, gain=${gain}]`
              )
            }>
            <Field label='Start row'><InputNumber size='small' value={startRow} onChange={(v) => setStartRow(Number(v ?? 0))} step={1} suffix='px' /></Field>
            <Field label='Start col'><InputNumber size='small' value={startCol} onChange={(v) => setStartCol(Number(v ?? 0))} step={1} suffix='px' /></Field>
            <Field label='Width'><InputNumber size='small' value={width} onChange={(v) => setWidth(Number(v ?? 0))} step={1} suffix='px' /></Field>
            <Field label='Height'><InputNumber size='small' value={height} onChange={(v) => setHeight(Number(v ?? 0))} step={1} suffix='px' /></Field>
            <Field label='H-bin'><InputNumber size='small' value={hBin} onChange={(v) => setHBin(Number(v ?? 0))} step={1} /></Field>
            <Field label='V-bin'><InputNumber size='small' value={vBin} onChange={(v) => setVBin(Number(v ?? 0))} step={1} /></Field>
            {gainField()}
          </ParamCommand>
          <Actions>
            <ActionButton label='Set default configuration' enabled={on('setDefaultConfiguration')} onSubmit={() => run(setDefaultConfigurationCmd(), 'setDefaultConfiguration')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Single exposure'>
          <ParamCommand
            name='Take & publish'
            enabled={on('takeAndPublishExposure')}
            onSubmit={() => run(takeAndPublishExposureCmd(it, gain), `takeAndPublishExposure [it=${it}s, gain=${gain}]`)}>
            <Field label='Integration'><InputNumber size='small' value={it} onChange={(v) => setIt(Number(v ?? 0))} step={0.1} suffix='s' /></Field>
            {gainField()}
          </ParamCommand>
          <ParamCommand
            name='Take & store'
            enabled={on('takeAndStoreExposure')}
            onSubmit={() => run(takeAndStoreExposureCmd(storeIt, gain), `takeAndStoreExposure [it=${storeIt}s, gain=${gain}]`)}>
            <Field label='Integration'><InputNumber size='small' value={storeIt} onChange={(v) => setStoreIt(Number(v ?? 0))} step={0.1} suffix='s' /></Field>
            {gainField()}
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Exposure loop'>
          <ParamCommand
            name='Start exposure loop'
            enabled={on('startExposureLoop')}
            onSubmit={() => run(startExposureLoopCmd(loopIt, rate, gain), `startExposureLoop [it=${loopIt}s, rate=${rate}Hz, gain=${gain}]`)}>
            <Field label='Integration'><InputNumber size='small' value={loopIt} onChange={(v) => setLoopIt(Number(v ?? 0))} step={0.05} suffix='s' /></Field>
            <Field label='Rate'><InputNumber size='small' value={rate} onChange={(v) => setRate(Number(v ?? 0))} step={1} suffix='Hz' /></Field>
            {gainField()}
          </ParamCommand>
          <Actions>
            <ActionButton label='Pause' enabled={on('pauseExposureLoop')} onSubmit={() => run(pauseExposureLoopCmd(), 'pauseExposureLoop')} />
            <ActionButton label='Restart' enabled={on('restartExposureLoop')} onSubmit={() => run(restartExposureLoopCmd(), 'restartExposureLoop')} />
            <ActionButton label='Stop loop' danger enabled={on('stopExposureLoop')} onSubmit={() => run(stopExposureLoopCmd(), 'stopExposureLoop')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='High-speed'>
          <ParamCommand
            name='Take high-speed exposures'
            enabled={on('takeHighSpeedExposures')}
            onSubmit={() =>
              run(
                takeHighSpeedExposuresCmd(hsIt, gain, hsRoi, hsFrameRate, hsDuration),
                `takeHighSpeedExposures [it=${hsIt}s, roiId=${hsRoi}, frameRate=${hsFrameRate}, duration=${hsDuration}s]`
              )
            }>
            <Field label='Integration'><InputNumber size='small' value={hsIt} onChange={(v) => setHsIt(Number(v ?? 0))} step={0.01} suffix='s' /></Field>
            <Field label='ROI id'><InputNumber size='small' value={hsRoi} onChange={(v) => setHsRoi(Number(v ?? 0))} step={1} /></Field>
            <Field label='Frame rate'><InputNumber size='small' value={hsFrameRate} onChange={(v) => setHsFrameRate(Number(v ?? 0))} step={10} suffix='fps' /></Field>
            <Field label='Duration'><InputNumber size='small' value={hsDuration} onChange={(v) => setHsDuration(Number(v ?? 0))} step={1} suffix='s' /></Field>
            {gainField()}
          </ParamCommand>
          <Actions>
            <ActionButton label='Abort high-speed' danger enabled={on('abortHighSpeedExposure')} onSubmit={() => run(abortHighSpeedExposureCmd(), 'abortHighSpeedExposure')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Recovery' danger>
          <ParamCommand
            name='Recover'
            danger
            enabled={on('recover')}
            onSubmit={() => run(recoverCmd(recMode, autoResume), `recover [mode=${recMode}, autoResume=${autoResume}]`)}>
            <Field label='Mode'>
              <Select<RecoverMode> size='small' value={recMode} onChange={setRecMode} options={RECOVER_MODES.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Auto-resume'>
              <div><Switch size='small' checked={autoResume} onChange={setAutoResume} /></div>
            </Field>
          </ParamCommand>
          <Actions>
            <ActionButton label='Reset camera' danger enabled={on('resetCamera')} onSubmit={() => run(resetCameraCmd(), 'resetCamera')} />
          </Actions>
        </CommandGroup>
      </CommandGroups>
    </Space>
  )
}

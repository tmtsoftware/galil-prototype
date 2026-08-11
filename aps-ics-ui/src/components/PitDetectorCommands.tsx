/*
 * PIT Detector Commands section (SDD §5.2 "Command Section"). Commands grouped by
 * context (command kit): Cooling, Detector config (configure + set default),
 * Exposure (take mmap / take & store / store / abort) and Recovery (recover +
 * reset camera). Gating is unchanged — `commandEnabled` mirrors the assembly
 * validate gate; everything is disabled while a command is in flight (`busy`).
 * Submit + result logging live in Main via `run`.
 */
import { InputNumber, Select, Space, Switch, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
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
  takeAndStoreExposureCmd,
  takeExposureCmd
} from '../models/pitDetector'
import type { AnalogGain, BitDepth, CmdName, CmsMode, RecoverMode, ShutterMode, StatusSnapshot } from '../models/pitDetector'

export const PitDetectorCommands = ({
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
  const [it, setIt] = useState(0.5)
  const [storeIt, setStoreIt] = useState(0.5)
  const [recMode, setRecMode] = useState<RecoverMode>('CLEAR')
  const [autoResume, setAutoResume] = useState(false)

  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)

  // Analog gain is shared across the exposure + config commands; a fresh Field each call.
  const gainField = (): React.JSX.Element => (
    <Field label='Gain'>
      <Select<AnalogGain> size='small' value={gain} onChange={setGain} options={ANALOG_GAINS.map((g) => ({ value: g, label: g }))} />
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
            enabled={on('configureDetectorCooling')}
            onSubmit={() => run(configureDetectorCoolingCmd(setPoint), `configureDetectorCooling [setPoint=${setPoint}]`)}>
            <Field label='Set point'>
              <InputNumber size='small' value={setPoint} onChange={(v) => setSetPoint(Number(v ?? 0))} step={1} suffix='°C' />
            </Field>
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Detector config'>
          <ParamCommand
            name='Configure detector'
            enabled={on('configureDetector')}
            onSubmit={() =>
              run(
                configureDetectorCmd(startRow, startCol, width, height, hBin, vBin, gain, bitDepth, shutter, cms),
                `configureDetector [roi=${width}x${height}@(${startCol},${startRow}), bin=${hBin}x${vBin}, gain=${gain}, bit=${bitDepth}, shutter=${shutter}, cms=${cms}]`
              )
            }>
            <Field label='Start row'><InputNumber size='small' value={startRow} onChange={(v) => setStartRow(Number(v ?? 0))} step={1} suffix='r' /></Field>
            <Field label='Start col'><InputNumber size='small' value={startCol} onChange={(v) => setStartCol(Number(v ?? 0))} step={1} suffix='c' /></Field>
            <Field label='Width'><InputNumber size='small' value={width} onChange={(v) => setWidth(Number(v ?? 0))} step={1} suffix='w' /></Field>
            <Field label='Height'><InputNumber size='small' value={height} onChange={(v) => setHeight(Number(v ?? 0))} step={1} suffix='h' /></Field>
            <Field label='H-bin'><InputNumber size='small' value={hBin} onChange={(v) => setHBin(Number(v ?? 0))} step={1} suffix='hB' /></Field>
            <Field label='V-bin'><InputNumber size='small' value={vBin} onChange={(v) => setVBin(Number(v ?? 0))} step={1} suffix='vB' /></Field>
            {gainField()}
            <Field label='Bit depth'>
              <Select<BitDepth> size='small' value={bitDepth} onChange={setBitDepth} options={BIT_DEPTHS.map((b) => ({ value: b, label: b }))} />
            </Field>
            <Field label='Shutter'>
              <Select<ShutterMode> size='small' value={shutter} onChange={setShutter} options={SHUTTER_MODES.map((sm) => ({ value: sm, label: sm }))} />
            </Field>
            <Field label='CMS'>
              <Select<CmsMode> size='small' value={cms} onChange={setCms} options={CMS_MODES.map((c) => ({ value: c, label: c }))} />
            </Field>
          </ParamCommand>
          <Actions>
            <ActionButton label='Set default configuration' enabled={on('setDefaultConfiguration')} onSubmit={() => run(setDefaultConfigurationCmd(), 'setDefaultConfiguration')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Exposure'>
          <ParamCommand
            name='Take exposure (mmap)'
            enabled={on('takeExposure')}
            onSubmit={() => run(takeExposureCmd(it, gain), `takeExposure [it=${it}s, gain=${gain}]`)}>
            <Field label='Integration'><InputNumber size='small' value={it} onChange={(v) => setIt(Number(v ?? 0))} step={0.1} suffix='s' /></Field>
            {gainField()}
          </ParamCommand>
          <ParamCommand
            name='Take & store exposure'
            enabled={on('takeAndStoreExposure')}
            onSubmit={() => run(takeAndStoreExposureCmd(storeIt, gain), `takeAndStoreExposure [it=${storeIt}s, gain=${gain}]`)}>
            <Field label='Integration'><InputNumber size='small' value={storeIt} onChange={(v) => setStoreIt(Number(v ?? 0))} step={0.1} suffix='s' /></Field>
            {gainField()}
          </ParamCommand>
          <Actions>
            <ActionButton label='Store exposure' enabled={on('storeExposure')} onSubmit={() => run(storeExposureCmd(), 'storeExposure')} />
            <ActionButton label='Abort exposure' danger enabled={on('abortExposure')} onSubmit={() => run(abortExposureCmd(), 'abortExposure')} />
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

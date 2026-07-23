/*
 * CollimatorUnit Commands section (SDD §4.3 "Command Section"). Commands grouped
 * by context (command kit): Setup, Scale (changeScale), Motion (the per-axis
 * position commands + their Stop) and Recovery (Abort error recovery). Gating is
 * unchanged — `commandEnabled` mirrors the assembly validate gate; everything is
 * disabled while a command is in flight (`busy`). Submit + result logging live in
 * Main via the `run` callback.
 *
 * configure/stop carry an `axis` choice in the ICD, but the base assembly
 * operates on all axes and ignores it, so the UI sends them without parameters.
 */
import { InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  POSITION_METHODS,
  abortRecoveryCmd,
  changeScaleCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionFrontAxisCmd,
  positionRearAxisCmd,
  stopCmd
} from '../models/collimatorUnit'
import type { CmdName, PositionMethod, StatusSnapshot } from '../models/collimatorUnit'

export const CollimatorUnitCommands = ({
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
  const [percent, setPercent] = useState<number>(0)
  const [frontMethod, setFrontMethod] = useState<PositionMethod>('ABSOLUTE')
  const [front, setFront] = useState<number>(0)
  const [rearMethod, setRearMethod] = useState<PositionMethod>('ABSOLUTE')
  const [rear, setRear] = useState<number>(0)

  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)

  return (
    <Space direction='vertical' size={8} style={{ width: '100%' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        ASSEMBLY COMMANDS
      </Typography.Text>
      <CommandGroups>
        <CommandGroup title='Setup'>
          <Actions>
            <ActionButton label='Home' enabled={on('home')} onSubmit={() => run(homeCmd(), 'home')} />
            <ActionButton label='Configure' enabled={on('configure')} onSubmit={() => run(configureCmd(), 'configure')} />
            <ActionButton label='Move to default position' enabled={on('moveToDefaultPosition')} onSubmit={() => run(moveToDefaultCmd(), 'moveToDefaultPosition')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Scale'>
          <ParamCommand
            name='Change scale'
            enabled={on('changeScale')}
            onSubmit={() => run(changeScaleCmd(percent), `changeScale [percentChange=${percent}]`)}>
            <Field label='Percent change'>
              <InputNumber size='small' value={percent} onChange={(v) => setPercent(Number(v ?? 0))} step={0.1} suffix='%' />
            </Field>
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Motion'>
          <ParamCommand
            name='Position front axis'
            enabled={on('positionFrontAxis')}
            onSubmit={() =>
              run(
                positionFrontAxisCmd(frontMethod, front),
                `positionFrontAxis [positioningMethod=${frontMethod}, positionValue=${front}]`
              )
            }>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={frontMethod} onChange={setFrontMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Position'>
              <InputNumber size='small' value={front} onChange={(v) => setFront(Number(v ?? 0))} step={0.1} suffix='mm' />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Position rear axis'
            enabled={on('positionRearAxis')}
            onSubmit={() =>
              run(
                positionRearAxisCmd(rearMethod, rear),
                `positionRearAxis [positioningMethod=${rearMethod}, positionValue=${rear}]`
              )
            }>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={rearMethod} onChange={setRearMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Position'>
              <InputNumber size='small' value={rear} onChange={(v) => setRear(Number(v ?? 0))} step={0.1} suffix='mm' />
            </Field>
          </ParamCommand>
          <Actions>
            <ActionButton label='Stop' danger enabled={on('stop')} onSubmit={() => run(stopCmd(), 'stop')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Recovery' danger>
          <Actions>
            <ActionButton label='Abort error recovery' danger enabled={on('abortErrorRecovery')} onSubmit={() => run(abortRecoveryCmd(), 'abortErrorRecovery')} />
          </Actions>
        </CommandGroup>
      </CommandGroups>
    </Space>
  )
}

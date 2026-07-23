/*
 * InsertionStage Commands section (SDD §4.3 "Command Section"). Commands grouped
 * by context (command kit): Setup, Source (select light source), Motion (the move
 * command + its Stop) and Recovery (Abort error recovery). Gating is unchanged —
 * `commandEnabled` mirrors the assembly validate gate; everything is disabled
 * while a command is in flight (`busy`). Submit + result logging live in Main via
 * the `run` callback, so every send and response land in the shared Command Log.
 */
import { InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  LIGHT_SOURCES,
  POSITION_METHODS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionStageCmd,
  selectSourceCmd,
  stopCmd
} from '../models/insertionStage'
import type { CmdName, LightSource, PositionMethod, StatusSnapshot } from '../models/insertionStage'

export const InsertionStageCommands = ({
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
  const [source, setSource] = useState<LightSource>('STIMULUS')
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [mm, setMm] = useState<number>(0)

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

        <CommandGroup title='Source'>
          <ParamCommand
            name='Select source'
            enabled={on('selectSource')}
            onSubmit={() => run(selectSourceCmd(source), `selectSource [lightSource=${source}]`)}>
            <Field label='Light source'>
              <Select<LightSource> size='small' value={source} onChange={setSource} options={LIGHT_SOURCES.map((s) => ({ value: s, label: s }))} />
            </Field>
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Motion'>
          <ParamCommand
            name='Position stage'
            enabled={on('positionStage')}
            onSubmit={() => run(positionStageCmd(method, mm), `positionStage [positioningMethod=${method}, value=${mm}]`)}>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={method} onChange={setMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Value'>
              <InputNumber size='small' value={mm} onChange={(v) => setMm(Number(v ?? 0))} step={0.1} suffix='mm' />
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

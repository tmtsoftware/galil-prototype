/*
 * SteeringBeamSplitterStage Commands section (SDD §4.3 "Command Section").
 * Commands grouped by context (command kit): Setup, Motion (the position command
 * + its Stop) and Recovery (Abort error recovery). Gating is unchanged —
 * `commandEnabled` mirrors the assembly validate gate; everything is disabled
 * while a command is in flight (`busy`). Submit + result logging live in Main via
 * the `run` callback.
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
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionBeamSplitterCmd,
  stopCmd
} from '../models/steeringBeamSplitter'
import type { CmdName, PositionMethod, StatusSnapshot } from '../models/steeringBeamSplitter'

export const SteeringBeamSplitterCommands = ({
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
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [x, setX] = useState<number>(0)
  const [y, setY] = useState<number>(0)

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

        <CommandGroup title='Motion'>
          <ParamCommand
            name='Position beam splitter'
            enabled={on('positionBeamSplitter')}
            onSubmit={() =>
              run(
                positionBeamSplitterCmd(method, x, y),
                `positionBeamSplitter [positioningMethod=${method}, xValue=${x}, yValue=${y}]`
              )
            }>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={method} onChange={setMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='X'>
              <InputNumber size='small' value={x} onChange={(v) => setX(Number(v ?? 0))} step={0.1} suffix='mm' />
            </Field>
            <Field label='Y'>
              <InputNumber size='small' value={y} onChange={(v) => setY(Number(v ?? 0))} step={0.1} suffix='mm' />
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

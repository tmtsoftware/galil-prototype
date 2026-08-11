/*
 * FiberSourceStage Commands section (SDD §4.3 "Command Section"). Commands grouped
 * by context (command kit): Setup, Position (the move + its Stop), Light (stub),
 * and Recovery (Abort). Buttons are gated by `commandEnabled` (mirrors the assembly
 * validate gate); everything is disabled while a command is in flight (`busy`). The
 * submit + result logging lives in Main via the `run` callback.
 *
 * NOTE: setSourceIntensity is STUBBED in the assembly this cut (controller-4 RIO
 * not wired) — it returns Completed without performing any light-source I/O.
 */
import { InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  POSITION_METHODS,
  SOURCE_POWERS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionSourceCmd,
  setSourceIntensityCmd,
  stopCmd
} from '../models/fiberSourceStage'
import type { CmdName, PositionMethod, SourcePower, StatusSnapshot } from '../models/fiberSourceStage'

export const FiberSourceStageCommands = ({
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
  const [z, setZ] = useState<number>(0)
  const [power, setPower] = useState<SourcePower>('OFF')
  const [intensity, setIntensity] = useState<number>(0)

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

        <CommandGroup title='Position'>
          <ParamCommand
            name='Position source (x, y, z)'
            enabled={on('positionSource')}
            onSubmit={() =>
              run(
                positionSourceCmd(method, x, y, z),
                `positionSource [positioningMethod=${method}, positionValueX=${x}, positionValueY=${y}, positionValueZ=${z}]`
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
            <Field label='Z'>
              <InputNumber size='small' value={z} onChange={(v) => setZ(Number(v ?? 0))} step={0.1} suffix='mm' />
            </Field>
          </ParamCommand>
          <Actions>
            <ActionButton label='Stop' danger enabled={on('stop')} onSubmit={() => run(stopCmd(), 'stop')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Light (stub)'>
          <ParamCommand
            name='Set source intensity'
            enabled={on('setSourceIntensity')}
            onSubmit={() =>
              run(
                setSourceIntensityCmd(power, intensity),
                `setSourceIntensity [sourcePower=${power}, sourceIntensity=${intensity}]`
              )
            }>
            <Field label='Power'>
              <Select<SourcePower> size='small' value={power} onChange={setPower} options={SOURCE_POWERS.map((p) => ({ value: p, label: p }))} />
            </Field>
            <Field label='Intensity'>
              <InputNumber size='small' value={intensity} onChange={(v) => setIntensity(Number(v ?? 0))} step={1} suffix='%' />
            </Field>
          </ParamCommand>
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

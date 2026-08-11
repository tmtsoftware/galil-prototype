/*
 * PitPupilMaskWheel Commands section (SDD §4.3 "Command Section"). Commands grouped
 * by context (command kit): Setup (Home / Configure / Move to default position),
 * Wheel (the discrete Select pupil mask + Position wheel + engineering Command
 * detent), Motion (Position motor + its Stop) and Recovery (Abort error recovery).
 * Gating is unchanged — `commandEnabled` mirrors the assembly validate gate and
 * everything is disabled while a command is in flight (`busy`). Submit + result
 * logging live in Main via the `run` callback.
 *
 * Adds, over a filter wheel, the pupil-mask selectPupilMask command and the
 * engineering commandDetent (EXTENDED|RETRACTED) detent drive.
 */
import { InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  DETENT_POSITIONS,
  POSITION_METHODS,
  POSITION_TARGETS,
  PUPIL_MASKS,
  WHEEL_POSITIONS,
  abortRecoveryCmd,
  commandDetentCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionMotorCmd,
  positionWheelCmd,
  selectPupilMaskCmd,
  stopCmd
} from '../models/pitPupilMaskWheel'
import type {
  CmdName,
  DetentPosition,
  PositionMethod,
  PositionTarget,
  PupilMask,
  StatusSnapshot,
  WheelPosition
} from '../models/pitPupilMaskWheel'

export const PitPupilMaskWheelCommands = ({
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
  const [mask, setMask] = useState<PupilMask>('PH-1-1')
  const [wheelPos, setWheelPos] = useState<WheelPosition>('1')
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [target, setTarget] = useState<PositionTarget>('WHEEL')
  const [value, setValue] = useState<number>(0)
  const [detent, setDetent] = useState<DetentPosition>('RETRACTED')

  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)
  const valueLabel = target === 'WHEEL' ? 'wheelPosition' : 'motorPosition'

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

        <CommandGroup title='Wheel'>
          <ParamCommand
            name='Select pupil mask'
            enabled={on('selectPupilMask')}
            onSubmit={() => run(selectPupilMaskCmd(mask), `selectPupilMask [pupilMask=${mask}]`)}>
            <Field label='Mask'>
              <Select<PupilMask> size='small' value={mask} onChange={setMask} options={PUPIL_MASKS.map((m) => ({ value: m, label: m }))} />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Position wheel'
            enabled={on('positionWheel')}
            onSubmit={() => run(positionWheelCmd(wheelPos), `positionWheel [positionNumber=${wheelPos}]`)}>
            <Field label='Position'>
              <Select<WheelPosition> size='small' value={wheelPos} onChange={setWheelPos} options={WHEEL_POSITIONS.map((n) => ({ value: n, label: n }))} />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Command detent'
            enabled={on('commandDetent')}
            onSubmit={() => run(commandDetentCmd(detent), `commandDetent [position=${detent}]`)}>
            <Field label='Detent'>
              <Select<DetentPosition> size='small' value={detent} onChange={setDetent} options={DETENT_POSITIONS.map((d) => ({ value: d, label: d }))} />
            </Field>
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Motion'>
          <ParamCommand
            name='Position motor'
            enabled={on('positionMotor')}
            onSubmit={() =>
              run(
                positionMotorCmd(method, target, value),
                `positionMotor [positioningMethod=${method}, positionTarget=${target}, ${valueLabel}=${value}]`
              )
            }>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={method} onChange={setMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Target'>
              <Select<PositionTarget> size='small' value={target} onChange={setTarget} options={POSITION_TARGETS.map((t) => ({ value: t, label: t }))} />
            </Field>
            <Field label='Value'>
              <InputNumber size='small' value={value} onChange={(v) => setValue(Number(v ?? 0))} step={target === 'WHEEL' ? 0.1 : 1} suffix={target === 'WHEEL' ? 'deg' : 'cts'} />
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

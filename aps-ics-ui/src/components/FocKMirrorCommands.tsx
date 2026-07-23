/*
 * FocKMirror Commands section (SDD §4.3 "Command Section"). Commands grouped by
 * context (command kit): Setup (Home / Configure / Move to default position),
 * Motion (Position K-Mirror + its Stop), Tracking (Set mode / Update PIT→PSH
 * offset / Update PIT correction offset / Restart tracking) and Recovery (Abort
 * error recovery). Gating is unchanged — `commandEnabled` mirrors the assembly
 * validate gate and everything is disabled while a command is in flight (`busy`).
 * Submit + result logging live in Main via the `run` callback.
 *
 * Phases 1-3 (MANUAL + SLEWING + TRACKING): positionKMirror (ABSOLUTE/RELATIVE,
 * degrees), setMode (selector offers all three modes; the assembly gates
 * setMode(TRACKING) on SLEW_COMPLETE), updatePitToPshOffset, updatePitCorrectionOffset
 * and restartTracking. The Tracking Control Actor runs the slew/track behaviour.
 */
import { InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  MODES_AVAILABLE,
  POSITION_METHODS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionKMirrorCmd,
  restartTrackingCmd,
  setModeCmd,
  stopCmd,
  updatePitCorrectionOffsetCmd,
  updatePitToPshOffsetCmd
} from '../models/focKMirror'
import type {
  CmdName,
  Mode,
  PositionMethod,
  StatusSnapshot
} from '../models/focKMirror'

export const FocKMirrorCommands = ({
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
  const [deg, setDeg] = useState<number>(0)
  const [mode, setMode] = useState<Mode>('MANUAL')
  const [pitOffset, setPitOffset] = useState<number>(0)
  const [pitCorrection, setPitCorrection] = useState<number>(0)

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
            name='Position K-Mirror'
            enabled={on('positionKMirror')}
            onSubmit={() =>
              run(
                positionKMirrorCmd(method, deg),
                `positionKMirror [positioningMethod=${method}, positionValue=${deg}]`
              )
            }>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={method} onChange={setMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Value'>
              <InputNumber size='small' value={deg} onChange={(v) => setDeg(Number(v ?? 0))} step={0.1} suffix='deg' />
            </Field>
          </ParamCommand>
          <Actions>
            <ActionButton label='Stop' danger enabled={on('stop')} onSubmit={() => run(stopCmd(), 'stop')} />
          </Actions>
        </CommandGroup>

        <CommandGroup title='Tracking'>
          <ParamCommand
            name='Set mode'
            enabled={on('setMode')}
            onSubmit={() => run(setModeCmd(mode), `setMode [mode=${mode}]`)}>
            <Field label='Mode'>
              <Select<Mode> size='small' value={mode} onChange={setMode} options={MODES_AVAILABLE.map((m) => ({ value: m, label: m }))} />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Update PIT→PSH offset'
            enabled={on('updatePitToPshOffset')}
            onSubmit={() =>
              run(
                updatePitToPshOffsetCmd(pitOffset),
                `updatePitToPshOffset [pitToPshRotationOffset=${pitOffset}]`
              )
            }>
            <Field label='Offset'>
              <InputNumber size='small' value={pitOffset} onChange={(v) => setPitOffset(Number(v ?? 0))} step={0.1} suffix='deg' />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Update PIT correction offset'
            enabled={on('updatePitCorrectionOffset')}
            onSubmit={() =>
              run(
                updatePitCorrectionOffsetCmd(pitCorrection),
                `updatePitCorrectionOffset [pitCorrectionOffset=${pitCorrection}]`
              )
            }>
            <Field label='Offset'>
              <InputNumber size='small' value={pitCorrection} onChange={(v) => setPitCorrection(Number(v ?? 0))} step={0.01} suffix='deg' />
            </Field>
          </ParamCommand>
          <Actions>
            <ActionButton label='Restart tracking (drop PIT)' enabled={on('restartTracking')} onSubmit={() => run(restartTrackingCmd(), 'restartTracking')} />
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

/*
 * CalibrationSourceStage Commands section (SDD §4.3 "Command Section"). Commands
 * grouped by context (command kit): Setup, Optic & slot, Position (the move + its
 * Stop), Light (stub), and Recovery (Abort). Buttons are gated by `commandEnabled`
 * (mirrors the assembly validate gate); everything is disabled while a command is
 * in flight (`busy`). The submit + result logging lives in Main via `run`.
 *
 * NOTE: setSourceIntensity and the source portion of setOpticAndSourceIntensity
 * are STUBBED in the assembly this cut (controller-3 RIO not wired) — they return
 * Completed without performing any light-source I/O.
 */
import { InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { ActionButton, Actions, CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  OPTICS,
  POSITION_METHODS,
  SLOTS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  setOpticAndSourceIntensityCmd,
  setOpticCmd,
  setPositionCmd,
  setSlotCmd,
  setSourceIntensityCmd,
  stopCmd
} from '../models/calibrationSourceStage'
import type { CmdName, Optic, PositionMethod, Slot, StatusSnapshot } from '../models/calibrationSourceStage'

export const CalibrationSourceStageCommands = ({
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
  const [optic, setOptic] = useState<Optic>('CALIBRATION_SOURCE')
  const [slot, setSlot] = useState<Slot>('1')
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [mm, setMm] = useState<number>(0)
  const [opticForIntensity, setOpticForIntensity] = useState<Optic>('CALIBRATION_SOURCE')
  const [comboIntensity, setComboIntensity] = useState<number>(0)
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

        <CommandGroup title='Optic & slot'>
          <ParamCommand
            name='Set optic'
            enabled={on('setOptic')}
            onSubmit={() => run(setOpticCmd(optic), `setOptic [optic=${optic}]`)}>
            <Field label='Optic'>
              <Select<Optic> size='small' value={optic} onChange={setOptic} options={OPTICS.map((o) => ({ value: o, label: o }))} />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Set slot'
            enabled={on('setSlot')}
            onSubmit={() => run(setSlotCmd(slot), `setSlot [slotNumber=${slot}]`)}>
            <Field label='Slot'>
              <Select<Slot> size='small' value={slot} onChange={setSlot} options={SLOTS.map((s) => ({ value: s, label: s }))} />
            </Field>
          </ParamCommand>
        </CommandGroup>

        <CommandGroup title='Position'>
          <ParamCommand
            name='Set position'
            enabled={on('setPosition')}
            onSubmit={() => run(setPositionCmd(method, mm), `setPosition [positioningMethod=${method}, positionValue=${mm}]`)}>
            <Field label='Method'>
              <Select<PositionMethod> size='small' value={method} onChange={setMethod} options={POSITION_METHODS.map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label='Position'>
              <InputNumber size='small' value={mm} onChange={(v) => setMm(Number(v ?? 0))} step={0.1} suffix='mm' />
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
            onSubmit={() => run(setSourceIntensityCmd(intensity), `setSourceIntensity [sourceIntensity=${intensity}]`)}>
            <Field label='Intensity'>
              <InputNumber size='small' value={intensity} onChange={(v) => setIntensity(Number(v ?? 0))} step={1} suffix='%' />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Set optic & source intensity'
            enabled={on('setOpticAndSourceIntensity')}
            onSubmit={() =>
              run(
                setOpticAndSourceIntensityCmd(opticForIntensity, comboIntensity),
                `setOpticAndSourceIntensity [optic=${opticForIntensity}, sourceIntensity=${comboIntensity}]`
              )
            }>
            <Field label='Optic'>
              <Select<Optic> size='small' value={opticForIntensity} onChange={setOpticForIntensity} options={OPTICS.map((o) => ({ value: o, label: o }))} />
            </Field>
            <Field label='Intensity'>
              <InputNumber size='small' value={comboIntensity} onChange={(v) => setComboIntensity(Number(v ?? 0))} step={1} suffix='%' />
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

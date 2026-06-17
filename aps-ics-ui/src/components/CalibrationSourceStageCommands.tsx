/*
 * CalibrationSourceStage Commands section (SDD §4.3 "Command Section"). One row
 * per command with its own Submit button and inline parameters. Buttons are gated
 * by `commandEnabled` (mirrors the assembly validate gate); everything is disabled
 * while a command is in flight (`busy`). The submit + result logging lives in Main
 * via the `run` callback.
 *
 * NOTE: setSourceIntensity and the source portion of setOpticAndSourceIntensity
 * are STUBBED in the assembly this cut (controller-3 RIO not wired) — they return
 * Completed without performing any light-source I/O.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
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
    <div style={{ display: 'flex', gap: 6, alignItems: 'center', justifyContent: 'flex-end', minWidth: 0 }}>
      {children}
    </div>
    <Button size='small' danger={danger} disabled={!enabled} onClick={onSubmit}>
      Submit
    </Button>
  </div>
)

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
    <Space direction='vertical' size={6} style={{ width: '100%' }}>
      <Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        ASSEMBLY COMMANDS
      </Text>

      <Row label='Home' enabled={on('home')} onSubmit={() => run(homeCmd(), 'home')} />
      <Row label='Configure' enabled={on('configure')} onSubmit={() => run(configureCmd(), 'configure')} />
      <Row
        label='Move to default position'
        enabled={on('moveToDefaultPosition')}
        onSubmit={() => run(moveToDefaultCmd(), 'moveToDefaultPosition')}
      />

      <Row
        label='Set optic'
        enabled={on('setOptic')}
        onSubmit={() => run(setOpticCmd(optic), `setOptic [optic=${optic}]`)}>
        <Select<Optic>
          size='small'
          value={optic}
          onChange={setOptic}
          style={{ width: 180 }}
          options={OPTICS.map((o) => ({ value: o, label: o }))}
        />
      </Row>

      <Row
        label='Set slot'
        enabled={on('setSlot')}
        onSubmit={() => run(setSlotCmd(slot), `setSlot [slotNumber=${slot}]`)}>
        <Select<Slot>
          size='small'
          value={slot}
          onChange={setSlot}
          style={{ width: 96 }}
          options={SLOTS.map((s) => ({ value: s, label: s }))}
        />
      </Row>

      <Row
        label='Set position'
        enabled={on('setPosition')}
        onSubmit={() => run(setPositionCmd(method, mm), `setPosition [positioningMethod=${method}, positionValue=${mm}]`)}>
        <Select<PositionMethod>
          size='small'
          value={method}
          onChange={setMethod}
          style={{ width: 120 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber size='small' value={mm} onChange={(v) => setMm(Number(v ?? 0))} step={0.1} suffix='mm' style={{ width: 108 }} />
      </Row>

      <Row
        label='Set optic & source intensity'
        enabled={on('setOpticAndSourceIntensity')}
        onSubmit={() =>
          run(
            setOpticAndSourceIntensityCmd(opticForIntensity, comboIntensity),
            `setOpticAndSourceIntensity [optic=${opticForIntensity}, sourceIntensity=${comboIntensity}]`
          )
        }>
        <Select<Optic>
          size='small'
          value={opticForIntensity}
          onChange={setOpticForIntensity}
          style={{ width: 180 }}
          options={OPTICS.map((o) => ({ value: o, label: o }))}
        />
        <InputNumber size='small' value={comboIntensity} onChange={(v) => setComboIntensity(Number(v ?? 0))} step={1} suffix='%' style={{ width: 96 }} />
      </Row>

      <Row
        label='Set source intensity'
        enabled={on('setSourceIntensity')}
        onSubmit={() => run(setSourceIntensityCmd(intensity), `setSourceIntensity [sourceIntensity=${intensity}]`)}>
        <InputNumber size='small' value={intensity} onChange={(v) => setIntensity(Number(v ?? 0))} step={1} suffix='%' style={{ width: 96 }} />
      </Row>

      <Row label='Stop' danger enabled={on('stop')} onSubmit={() => run(stopCmd(), 'stop')} />
      <Row
        label='Abort error recovery'
        danger
        enabled={on('abortErrorRecovery')}
        onSubmit={() => run(abortRecoveryCmd(), 'abortErrorRecovery')}
      />
    </Space>
  )
}

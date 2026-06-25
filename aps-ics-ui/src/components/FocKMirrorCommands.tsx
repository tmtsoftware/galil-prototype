/*
 * FocKMirror Commands section (SDD §4.3 "Command Section"). One row per command
 * with its own Submit button and inline parameters. Buttons are gated by
 * `commandEnabled` (mirrors the assembly validate gate); everything is disabled
 * while a command is in flight (`busy`). The submit + result logging lives in Main
 * via the `run` callback.
 *
 * Phases 1-3 (MANUAL + SLEWING + TRACKING): positionKMirror (ABSOLUTE/RELATIVE,
 * degrees), setMode (selector offers all three modes; the assembly gates
 * setMode(TRACKING) on SLEW_COMPLETE), updatePitToPshOffset, updatePitCorrectionOffset
 * and restartTracking. The Tracking Control Actor runs the slew/track behaviour.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
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
    <div
      style={{
        display: 'flex',
        gap: 6,
        alignItems: 'center',
        justifyContent: 'flex-end',
        minWidth: 0
      }}>
      {children}
    </div>
    <Button size='small' danger={danger} disabled={!enabled} onClick={onSubmit}>
      Submit
    </Button>
  </div>
)

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
    <Space direction='vertical' size={6} style={{ width: '100%' }}>
      <Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        ASSEMBLY COMMANDS
      </Text>

      <Row
        label='Home'
        enabled={on('home')}
        onSubmit={() => run(homeCmd(), 'home')}
      />
      <Row
        label='Configure'
        enabled={on('configure')}
        onSubmit={() => run(configureCmd(), 'configure')}
      />
      <Row
        label='Move to default position'
        enabled={on('moveToDefaultPosition')}
        onSubmit={() => run(moveToDefaultCmd(), 'moveToDefaultPosition')}
      />

      <Row
        label='Set mode'
        enabled={on('setMode')}
        onSubmit={() => run(setModeCmd(mode), `setMode [mode=${mode}]`)}>
        <Select<Mode>
          size='small'
          value={mode}
          onChange={setMode}
          style={{ width: 140 }}
          options={MODES_AVAILABLE.map((m) => ({ value: m, label: m }))}
        />
      </Row>

      <Row
        label='Update PIT→PSH offset'
        enabled={on('updatePitToPshOffset')}
        onSubmit={() =>
          run(
            updatePitToPshOffsetCmd(pitOffset),
            `updatePitToPshOffset [pitToPshRotationOffset=${pitOffset}]`
          )
        }>
        <InputNumber
          size='small'
          value={pitOffset}
          onChange={(v) => setPitOffset(Number(v ?? 0))}
          step={0.1}
          suffix='deg'
          style={{ width: 108 }}
        />
      </Row>

      <Row
        label='Update PIT correction offset'
        enabled={on('updatePitCorrectionOffset')}
        onSubmit={() =>
          run(
            updatePitCorrectionOffsetCmd(pitCorrection),
            `updatePitCorrectionOffset [pitCorrectionOffset=${pitCorrection}]`
          )
        }>
        <InputNumber
          size='small'
          value={pitCorrection}
          onChange={(v) => setPitCorrection(Number(v ?? 0))}
          step={0.01}
          suffix='deg'
          style={{ width: 108 }}
        />
      </Row>

      <Row
        label='Restart tracking (drop PIT)'
        enabled={on('restartTracking')}
        onSubmit={() => run(restartTrackingCmd(), 'restartTracking')}
      />

      <Row
        label='Position K-Mirror'
        enabled={on('positionKMirror')}
        onSubmit={() =>
          run(
            positionKMirrorCmd(method, deg),
            `positionKMirror [positioningMethod=${method}, positionValue=${deg}]`
          )
        }>
        <Select<PositionMethod>
          size='small'
          value={method}
          onChange={setMethod}
          style={{ width: 120 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber
          size='small'
          value={deg}
          onChange={(v) => setDeg(Number(v ?? 0))}
          step={0.1}
          suffix='deg'
          style={{ width: 108 }}
        />
      </Row>

      <Row
        label='Stop'
        danger
        enabled={on('stop')}
        onSubmit={() => run(stopCmd(), 'stop')}
      />
      <Row
        label='Abort error recovery'
        danger
        enabled={on('abortErrorRecovery')}
        onSubmit={() => run(abortRecoveryCmd(), 'abortErrorRecovery')}
      />
    </Space>
  )
}

/*
 * PupilMaskStage Commands section (SDD §4.3 "Command Section"). One row per
 * command with its own Submit button and inline parameters. Buttons are gated by
 * `commandEnabled` (mirrors the assembly validate gate); everything is disabled
 * while a command is in flight (`busy`). The submit + result logging lives in Main
 * via the `run` callback.
 *
 * positionMaskStage drives X/Y (mm) and Phi (deg, rotational) from a single
 * command.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import {
  POSITION_METHODS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionMaskStageCmd,
  stopCmd
} from '../models/pupilMaskStage'
import type { CmdName, PositionMethod, StatusSnapshot } from '../models/pupilMaskStage'

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

export const PupilMaskStageCommands = ({
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
  const [phi, setPhi] = useState<number>(0)

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
        label='Position mask stage (x, y, φ)'
        enabled={on('positionMaskStage')}
        onSubmit={() =>
          run(
            positionMaskStageCmd(method, x, y, phi),
            `positionMaskStage [positioningMethod=${method}, positionValueX=${x}, positionValueY=${y}, positionValuePhi=${phi}]`
          )
        }>
        <Select<PositionMethod>
          size='small'
          value={method}
          onChange={setMethod}
          style={{ width: 104 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber size='small' value={x} onChange={(v) => setX(Number(v ?? 0))} step={0.1} prefix='x' suffix='mm' style={{ width: 92 }} />
        <InputNumber size='small' value={y} onChange={(v) => setY(Number(v ?? 0))} step={0.1} prefix='y' suffix='mm' style={{ width: 92 }} />
        <InputNumber size='small' value={phi} onChange={(v) => setPhi(Number(v ?? 0))} step={0.1} prefix='φ' suffix='deg' style={{ width: 96 }} />
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

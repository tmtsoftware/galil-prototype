/*
 * FiberSourceStage Commands section (SDD §4.3 "Command Section"). One row per
 * command with its own Submit button and inline parameters. Buttons are gated by
 * `commandEnabled` (mirrors the assembly validate gate); everything is disabled
 * while a command is in flight (`busy`). The submit + result logging lives in Main
 * via the `run` callback.
 *
 * NOTE: setSourceIntensity is STUBBED in the assembly this cut (controller-4 RIO
 * not wired) — it returns Completed without performing any light-source I/O.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
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
        label='Position source (x, y, z)'
        enabled={on('positionSource')}
        onSubmit={() =>
          run(
            positionSourceCmd(method, x, y, z),
            `positionSource [positioningMethod=${method}, positionValueX=${x}, positionValueY=${y}, positionValueZ=${z}]`
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
        <InputNumber size='small' value={z} onChange={(v) => setZ(Number(v ?? 0))} step={0.1} prefix='z' suffix='mm' style={{ width: 92 }} />
      </Row>

      <Row
        label='Set source intensity'
        enabled={on('setSourceIntensity')}
        onSubmit={() =>
          run(
            setSourceIntensityCmd(power, intensity),
            `setSourceIntensity [sourcePower=${power}, sourceIntensity=${intensity}]`
          )
        }>
        <Select<SourcePower>
          size='small'
          value={power}
          onChange={setPower}
          style={{ width: 88 }}
          options={SOURCE_POWERS.map((p) => ({ value: p, label: p }))}
        />
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

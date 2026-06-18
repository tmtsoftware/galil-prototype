/*
 * SteeringBeamSplitterStage Commands section (SDD §4.3 "Command Section"). One
 * row per command with its own Submit button and inline parameters. Buttons are
 * gated by `commandEnabled` (mirrors the assembly validate gate); everything is
 * disabled while a command is in flight (`busy`).
 *
 * The submit + result logging lives in Main via the `run` callback, so every
 * command's send and response land in the shared Command/Event Log.
 *
 * configure/stop carry an `axis` choice in the ICD, but the base assembly
 * operates on all axes and ignores it, so the UI sends them without parameters
 * (matching the assembly's validate gate).
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
  positionBeamSplitterCmd,
  stopCmd
} from '../models/steeringBeamSplitter'
import type { CmdName, PositionMethod, StatusSnapshot } from '../models/steeringBeamSplitter'

const { Text } = Typography

// A single command row: label, right-justified inline params, Submit pinned right.
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
        label='Position beam splitter'
        enabled={on('positionBeamSplitter')}
        onSubmit={() =>
          run(
            positionBeamSplitterCmd(method, x, y),
            `positionBeamSplitter [positioningMethod=${method}, xValue=${x}, yValue=${y}]`
          )
        }>
        <Select<PositionMethod>
          size='small'
          value={method}
          onChange={setMethod}
          style={{ width: 120 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber size='small' value={x} onChange={(v) => setX(Number(v ?? 0))} step={0.1} prefix='x' suffix='mm' style={{ width: 108 }} />
        <InputNumber size='small' value={y} onChange={(v) => setY(Number(v ?? 0))} step={0.1} prefix='y' suffix='mm' style={{ width: 108 }} />
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

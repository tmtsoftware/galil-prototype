/*
 * Assembly Commands section (SDD §4.3 "Command Section"). One row per command,
 * each with its own Submit button and inline parameters, matching Figure 43.
 * Buttons are gated by `commandEnabled` (mirrors the assembly validate gate);
 * everything is disabled while a command is in flight (`busy`).
 *
 * The actual submit + result logging lives in Main via the `run` callback, so
 * every command's send and response land in the shared Command/Event Log.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import {
  LIGHT_SOURCES,
  POSITION_METHODS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionStageCmd,
  selectSourceCmd,
  stopCmd
} from '../models/insertionStage'
import type { CmdName, LightSource, PositionMethod, StatusSnapshot } from '../models/insertionStage'

const { Text } = Typography

// A single command row: label on the left, inline params in the middle, Submit
// on the right. `danger` styles stop/abort red.
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
      // [label] [params: right-justified] [Submit]. The 1fr params column
      // right-aligns its controls next to Submit; Submit stays pinned right and
      // aligned across rows, and param-less rows leave the column blank.
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

export const InsertionStageCommands = ({
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
  const [source, setSource] = useState<LightSource>('STIMULUS')
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [mm, setMm] = useState<number>(0)

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
        label='Select source'
        enabled={on('selectSource')}
        onSubmit={() => run(selectSourceCmd(source), `selectSource [lightSource=${source}]`)}>
        <Select<LightSource>
          size='small'
          value={source}
          onChange={setSource}
          style={{ width: 128 }}
          options={LIGHT_SOURCES.map((s) => ({ value: s, label: s }))}
        />
      </Row>

      <Row
        label='Position stage'
        enabled={on('positionStage')}
        onSubmit={() => run(positionStageCmd(method, mm), `positionStage [positioningMethod=${method}, value=${mm}]`)}>
        <Select<PositionMethod>
          size='small'
          value={method}
          onChange={setMethod}
          style={{ width: 128 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber size='small' value={mm} onChange={(v) => setMm(Number(v ?? 0))} step={0.1} suffix='mm' style={{ width: 104 }} />
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

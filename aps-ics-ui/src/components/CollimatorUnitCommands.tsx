/*
 * CollimatorUnit Commands section (SDD §4.3 "Command Section"). One row per
 * command with its own Submit button and inline parameters. Buttons are gated by
 * `commandEnabled` (mirrors the assembly validate gate); everything is disabled
 * while a command is in flight (`busy`). The submit + result logging lives in
 * Main via the `run` callback.
 *
 * configure/stop carry an `axis` choice in the ICD, but the base assembly
 * operates on all axes and ignores it, so the UI sends them without parameters.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import {
  POSITION_METHODS,
  abortRecoveryCmd,
  changeScaleCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionFrontAxisCmd,
  positionRearAxisCmd,
  stopCmd
} from '../models/collimatorUnit'
import type { CmdName, PositionMethod, StatusSnapshot } from '../models/collimatorUnit'

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

export const CollimatorUnitCommands = ({
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
  const [percent, setPercent] = useState<number>(0)
  const [frontMethod, setFrontMethod] = useState<PositionMethod>('ABSOLUTE')
  const [front, setFront] = useState<number>(0)
  const [rearMethod, setRearMethod] = useState<PositionMethod>('ABSOLUTE')
  const [rear, setRear] = useState<number>(0)

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
        label='Change scale'
        enabled={on('changeScale')}
        onSubmit={() => run(changeScaleCmd(percent), `changeScale [percentChange=${percent}]`)}>
        <InputNumber size='small' value={percent} onChange={(v) => setPercent(Number(v ?? 0))} step={0.1} suffix='%' style={{ width: 108 }} />
      </Row>

      <Row
        label='Position front axis'
        enabled={on('positionFrontAxis')}
        onSubmit={() =>
          run(
            positionFrontAxisCmd(frontMethod, front),
            `positionFrontAxis [positioningMethod=${frontMethod}, positionValue=${front}]`
          )
        }>
        <Select<PositionMethod>
          size='small'
          value={frontMethod}
          onChange={setFrontMethod}
          style={{ width: 120 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber size='small' value={front} onChange={(v) => setFront(Number(v ?? 0))} step={0.1} suffix='mm' style={{ width: 108 }} />
      </Row>

      <Row
        label='Position rear axis'
        enabled={on('positionRearAxis')}
        onSubmit={() =>
          run(
            positionRearAxisCmd(rearMethod, rear),
            `positionRearAxis [positioningMethod=${rearMethod}, positionValue=${rear}]`
          )
        }>
        <Select<PositionMethod>
          size='small'
          value={rearMethod}
          onChange={setRearMethod}
          style={{ width: 120 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <InputNumber size='small' value={rear} onChange={(v) => setRear(Number(v ?? 0))} step={0.1} suffix='mm' style={{ width: 108 }} />
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

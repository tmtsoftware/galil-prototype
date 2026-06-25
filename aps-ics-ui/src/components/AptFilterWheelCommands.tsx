/*
 * AptFilterWheel Commands section (SDD §4.3 "Command Section"). One row per
 * command with its own Submit button and inline parameters. Buttons are gated by
 * `commandEnabled` (mirrors the assembly validate gate); everything is disabled
 * while a command is in flight (`busy`). The submit + result logging lives in Main
 * via the `run` callback.
 */
import { Button, InputNumber, Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import {
  FILTERS,
  POSITION_METHODS,
  POSITION_TARGETS,
  WHEEL_POSITIONS,
  abortRecoveryCmd,
  commandEnabled,
  configureCmd,
  homeCmd,
  moveToDefaultCmd,
  positionMotorCmd,
  positionWheelCmd,
  selectFilterCmd,
  stopCmd
} from '../models/aptFilterWheel'
import type {
  CmdName,
  Filter,
  PositionMethod,
  PositionTarget,
  StatusSnapshot,
  WheelPosition
} from '../models/aptFilterWheel'

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

export const AptFilterWheelCommands = ({
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
  const [filter, setFilter] = useState<Filter>('ND1')
  const [wheelPos, setWheelPos] = useState<WheelPosition>('1')
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [target, setTarget] = useState<PositionTarget>('WHEEL')
  const [value, setValue] = useState<number>(0)

  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)
  const valueLabel = target === 'WHEEL' ? 'wheelPosition' : 'motorPosition'

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
        label='Select filter'
        enabled={on('selectFilter')}
        onSubmit={() =>
          run(selectFilterCmd(filter), `selectFilter [filter=${filter}]`)
        }>
        <Select<Filter>
          size='small'
          value={filter}
          onChange={setFilter}
          style={{ width: 120 }}
          options={FILTERS.map((f) => ({ value: f, label: f }))}
        />
      </Row>

      <Row
        label='Position wheel'
        enabled={on('positionWheel')}
        onSubmit={() =>
          run(
            positionWheelCmd(wheelPos),
            `positionWheel [positionNumber=${wheelPos}]`
          )
        }>
        <Select<WheelPosition>
          size='small'
          value={wheelPos}
          onChange={setWheelPos}
          style={{ width: 84 }}
          options={WHEEL_POSITIONS.map((n) => ({ value: n, label: n }))}
        />
      </Row>

      <Row
        label='Position motor'
        enabled={on('positionMotor')}
        onSubmit={() =>
          run(
            positionMotorCmd(method, target, value),
            `positionMotor [positioningMethod=${method}, positionTarget=${target}, ${valueLabel}=${value}]`
          )
        }>
        <Select<PositionMethod>
          size='small'
          value={method}
          onChange={setMethod}
          style={{ width: 110 }}
          options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
        />
        <Select<PositionTarget>
          size='small'
          value={target}
          onChange={setTarget}
          style={{ width: 92 }}
          options={POSITION_TARGETS.map((t) => ({ value: t, label: t }))}
        />
        <InputNumber
          size='small'
          value={value}
          onChange={(v) => setValue(Number(v ?? 0))}
          step={target === 'WHEEL' ? 0.1 : 1}
          suffix={target === 'WHEEL' ? 'deg' : 'cts'}
          style={{ width: 110 }}
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

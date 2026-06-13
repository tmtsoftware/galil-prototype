/*
 * InsertionStage command panel. Submits Setups through the gateway-resolved
 * CommandService (submit -> queryFinal for the long-running result). Buttons are
 * enabled/disabled by `commandEnabled`, which mirrors the assembly's validate
 * gate, so e.g. only "Abort Recovery" is live while commandState=ERROR_RECOVERY.
 */
import { Button, Card, InputNumber, Select, Space, Tag, Typography, message } from 'antd'
import type { CommandService, Setup, SubmitResponse } from '@tmtsoftware/esw-ts'
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
import type {
  CmdName,
  LightSource,
  PositionMethod,
  StatusSnapshot
} from '../models/insertionStage'

const respColor = (t?: SubmitResponse['_type']): string =>
  t === 'Completed'
    ? 'green'
    : t === 'Started'
      ? 'blue'
      : t === 'Cancelled'
        ? 'orange'
        : t === undefined
          ? 'default'
          : 'red'

// queryFinal timeout (s): home can run several minutes (HCD home watchdog ~3min,
// assembly homeHcdTimeout 4min); 300s is a safe ceiling above the HCD watchdogs.
const FINAL_TIMEOUT_S = 300

export const InsertionStageCommands = ({
  commandService,
  status
}: {
  commandService?: CommandService
  status: StatusSnapshot
}): React.JSX.Element => {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<SubmitResponse>()
  const [source, setSource] = useState<LightSource>('STIMULUS')
  const [method, setMethod] = useState<PositionMethod>('ABSOLUTE')
  const [mm, setMm] = useState<number>(0)

  const ready = commandService !== undefined
  const on = (cmd: CmdName): boolean => commandEnabled(cmd, status, ready, busy)

  const run = async (setup: Setup): Promise<void> => {
    if (!commandService) return
    setBusy(true)
    try {
      let res = await commandService.submit(setup)
      if (res._type === 'Started') res = await commandService.queryFinal(res.runId, FINAL_TIMEOUT_S)
      setResult(res)
    } catch (e) {
      message.error((e as Error).message)
      setResult(undefined)
    } finally {
      setBusy(false)
    }
  }

  const runId = (result as { runId?: string } | undefined)?.runId

  return (
    <Card title='InsertionStage — Commands' style={{ width: '28rem' }}>
      <Space direction='vertical' size='middle' style={{ width: '100%' }}>
        <Space wrap>
          <Button onClick={() => run(homeCmd())} disabled={!on('home')} loading={busy}>
            Home
          </Button>
          <Button onClick={() => run(configureCmd())} disabled={!on('configure')}>
            Configure
          </Button>
          <Button onClick={() => run(moveToDefaultCmd())} disabled={!on('moveToDefaultPosition')}>
            Move To Default
          </Button>
          <Button danger onClick={() => run(stopCmd())} disabled={!on('stop')}>
            Stop
          </Button>
          <Button danger onClick={() => run(abortRecoveryCmd())} disabled={!on('abortErrorRecovery')}>
            Abort Recovery
          </Button>
        </Space>

        <Space>
          <Select<LightSource>
            value={source}
            onChange={(v) => setSource(v)}
            style={{ width: 130 }}
            options={LIGHT_SOURCES.map((s) => ({ value: s, label: s }))}
          />
          <Button type='primary' disabled={!on('selectSource')} onClick={() => run(selectSourceCmd(source))}>
            Select Source
          </Button>
        </Space>

        <Space>
          <Select<PositionMethod>
            value={method}
            onChange={(v) => setMethod(v)}
            style={{ width: 130 }}
            options={POSITION_METHODS.map((m) => ({ value: m, label: m }))}
          />
          <InputNumber
            value={mm}
            onChange={(v) => setMm(Number(v ?? 0))}
            step={0.1}
            addonAfter='mm'
          />
          <Button type='primary' disabled={!on('positionStage')} onClick={() => run(positionStageCmd(method, mm))}>
            Move
          </Button>
        </Space>

        <Space>
          <Typography.Text type='secondary'>last result</Typography.Text>
          <Tag color={respColor(result?._type)}>{result?._type ?? '—'}</Tag>
          {runId && <Typography.Text code>{runId}</Typography.Text>}
        </Space>
      </Space>
    </Card>
  )
}
/*
 * HCD panel (SDD §4.3 — HCDs). Shown when an HCD is selected in the component
 * tree. Assembly-UI-level awareness of a Galil motion HCD:
 *   - registration (up/down) via Location Service tracking (shared liveness),
 *   - the HCD JVM's process CPU load (REQ-2-APS-0621) from its `cpuLoad` event,
 *   - the assemblies bound to this HCD (clickable to jump to them),
 *   - a link to the HCD's own engineering HMI console (new tab).
 *
 * We LINK to the HMI rather than embed it: the HMI is a direct, unauthenticated
 * per-controller console (galil-hcd HmiServer, port 9090 + id) that bypasses the
 * gateway and AAS auth, so it stays in its own browser context. Its host comes
 * from the cpuLoad event's `hostname` (the machine the HCD runs on); the browser
 * must be able to reach host:port (same requirement as the Config Service reads).
 */
import { Button, Space, Tooltip, Typography } from 'antd'
import React, { useEffect, useState } from 'react'
import { EventKey, EventName, EventService } from '@tmtsoftware/esw-ts'
import type { Event, Subscription } from '@tmtsoftware/esw-ts'
import { useAuth } from '../hooks/useAuth'
import { useComponentLiveness } from '../contexts/ComponentLivenessContext'
import { StateChip, StateStrip } from './statusLayout'
import { LivenessDot, LivenessTag } from './LivenessIndicator'
import { HCDS } from './hcds'
import { REGISTRY } from './registry'

const firstValue = (e: Event, name: string): unknown =>
  e.paramSet.find((p) => p.keyName === name)?.values?.[0]

type Cpu = { proc: number; sys: number; cores: number; pid: number; hostname?: string }

export const HcdPanel = ({
  hcdKey,
  onSelectComponent
}: {
  hcdKey: string
  onSelectComponent: (key: string) => void
}): React.JSX.Element => {
  const hcd = HCDS[hcdKey]
  const liveness = useComponentLiveness()
  const live = liveness[hcdKey] ?? 'unknown'
  const up = live === 'up'

  const { auth } = useAuth()
  const isAuthenticated = auth?.isAuthenticated() ?? false
  const [cpu, setCpu] = useState<Cpu>()

  // Subscribe to this HCD's own cpuLoad event (published per-JVM under its prefix).
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let sub: Subscription | undefined
    let cancelled = false
    setCpu(undefined)
    const keys = new Set([new EventKey(hcd.prefix, new EventName('cpuLoad'))])
    EventService(authData).then((es) => {
      if (cancelled) return
      sub = es.subscribe(keys)((e: Event) => {
        if (e.eventId === '-1') return
        const proc = firstValue(e, 'processCpuLoad') as number | undefined
        if (proc === undefined) return
        setCpu({
          proc,
          sys: (firstValue(e, 'systemCpuLoad') as number | undefined) ?? NaN,
          cores: (firstValue(e, 'availableProcessors') as number | undefined) ?? 0,
          pid: (firstValue(e, 'pid') as number | undefined) ?? -1,
          hostname: firstValue(e, 'hostname') as string | undefined
        })
      })
    })
    return () => {
      cancelled = true
      sub?.cancel()
    }
  }, [auth, isAuthenticated, hcd])

  // CPU % is only meaningful while up (a dead JVM can replay a retained ghost).
  const pct = up && cpu && cpu.proc >= 0 ? cpu.proc * 100 : undefined
  const fmtPct = (v: number | undefined): string => (v === undefined ? '—' : `${v.toFixed(0)}%`)
  const cpuColor = pct === undefined ? '#8c8c8c' : pct >= 70 ? '#cf1322' : pct >= 55 ? '#d46b08' : '#389e0d'

  const host = cpu?.hostname || window.location.hostname
  const hmiUrl = `http://${host}:${hcd.hmiPort}`

  const bound = hcd.assemblyKeys
    .map((k) => ({ key: k, label: REGISTRY[k]?.label ?? k }))
    .sort((a, b) => a.label.localeCompare(b.label))

  return (
    <Space direction='vertical' size={16} style={{ width: '100%', maxWidth: 720 }}>
      <StateStrip>
        <StateChip label='Registration'><LivenessTag state={live} /></StateChip>
        <StateChip label='Process CPU'>
          <Tooltip title={cpu ? `pid ${cpu.pid} · system ${fmtPct(up && cpu.sys >= 0 ? cpu.sys * 100 : undefined)} · ${cpu.cores} cores · REQ-2-APS-0621 ≤ 70%` : 'waiting for cpuLoad'}>
            <span style={{ color: cpuColor, fontWeight: 600, fontFamily: 'monospace' }}>{fmtPct(pct)}</span>
          </Tooltip>
        </StateChip>
        <StateChip label='Host'><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{host}</span></StateChip>
        <StateChip label='PID'><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{up && cpu ? cpu.pid : '—'}</span></StateChip>
      </StateStrip>

      <div style={{ border: '1px solid #e8e8e8', borderRadius: 10, padding: '12px 14px' }}>
        <Typography.Text strong>Engineering HMI</Typography.Text>
        <div style={{ color: '#8c8c8c', fontSize: 12, margin: '4px 0 10px' }}>
          Per-controller console served by the HCD itself. It commands the controller directly, bypassing the gateway and AAS auth — use with care. Opens in a new browser tab.
        </div>
        <Space wrap>
          <Button type='primary' disabled={!up} href={up ? hmiUrl : undefined} target='_blank' rel='noreferrer'>
            Open HCD HMI ↗
          </Button>
          <Typography.Text code style={{ fontSize: 12 }}>{hmiUrl}</Typography.Text>
        </Space>
        {!up && (
          <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 8 }}>
            Available when the HCD is running (currently {live}).
          </div>
        )}
      </div>

      <div>
        <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
          ASSEMBLIES ON THIS HCD ({bound.length})
        </Typography.Text>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
          {bound.map((b) => (
            <div
              key={b.key}
              onClick={() => onSelectComponent(b.key)}
              role='button'
              tabIndex={0}
              onKeyDown={(ev) => { if (ev.key === 'Enter' || ev.key === ' ') onSelectComponent(b.key) }}
              style={{ display: 'flex', alignItems: 'center', gap: 4, border: '1px solid #e8e8e8', borderRadius: 8, padding: '5px 10px', cursor: 'pointer', background: '#fff', fontSize: 13 }}>
              <LivenessDot state={liveness[b.key] ?? 'unknown'} />
              {b.label}
            </div>
          ))}
        </div>
      </div>
    </Space>
  )
}

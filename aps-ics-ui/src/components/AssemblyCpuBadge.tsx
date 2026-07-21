import * as React from 'react'
import { useEffect, useState } from 'react'
import { Tooltip } from 'antd'
import { EventKey, EventName, EventService, Prefix } from '@tmtsoftware/esw-ts'
import type { Event, Subscription } from '@tmtsoftware/esw-ts'
import { useAuth } from '../hooks/useAuth'

/*
 * Global CPU-load badge for the ICS assembly container.
 *
 * The combined IcsAssembliesContainer runs every ICS assembly in ONE JVM, and
 * IcsContainerApp labels that JVM's single cpuLoad event with this prefix
 * (System.setProperty("cpuload.source", "APS.ICS.IcsAssemblies")). getProcessCpuLoad
 * is per-PROCESS, so this one event is the whole assembly JVM's load — the
 * REQ-2-APS-0621 measurand for that process — not any single assembly's.
 */
const CONTAINER_PREFIX = Prefix.fromString('APS.ICS.IcsAssemblies')
const CPU_EVENT = 'cpuLoad'

type Cpu = { proc: number; sys: number; cores: number; pid: number }

const firstValue = (e: Event, name: string): unknown =>
  e.paramSet.find((p) => p.keyName === name)?.values?.[0]

export const AssemblyCpuBadge = (): React.JSX.Element => {
  const { auth } = useAuth()
  const isAuthenticated = auth?.isAuthenticated() ?? false
  const [cpu, setCpu] = useState<Cpu>()

  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let sub: Subscription | undefined
    let cancelled = false
    const keys = new Set([new EventKey(CONTAINER_PREFIX, new EventName(CPU_EVENT))])
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
          pid: (firstValue(e, 'pid') as number | undefined) ?? -1
        })
      })
    })
    return () => {
      cancelled = true
      sub?.cancel()
    }
  }, [auth, isAuthenticated])

  const pct = cpu && cpu.proc >= 0 ? cpu.proc * 100 : undefined
  const sysPct = cpu && cpu.sys >= 0 ? cpu.sys * 100 : undefined
  const fmt = (v: number | undefined): string => (v === undefined ? '—' : `${v.toFixed(0)}%`)
  // Colour keyed to the REQ-2-APS-0621 70% ceiling: green < 55, amber 55-70, red >= 70.
  const color = pct === undefined ? '#9CA3AF' : pct >= 70 ? '#FCA5A5' : pct >= 55 ? '#FCD34D' : '#6EE7B7'

  const tip =
    cpu === undefined
      ? 'Assembly container CPU (REQ-2-APS-0621: APS CPU <= 70%) — waiting for cpuLoad event'
      : `Assembly container JVM (pid ${cpu.pid}) — process ${fmt(pct)} · system ${fmt(sysPct)} · ${cpu.cores} cores. REQ-2-APS-0621: APS CPU <= 70%.`

  return (
    <Tooltip title={tip}>
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          fontFamily: 'monospace',
          fontSize: 13,
          padding: '2px 10px',
          borderRadius: 4,
          background: 'rgba(255,255,255,0.08)',
          color
        }}
      >
        <span style={{ width: 7, height: 7, borderRadius: '50%', background: color }} />
        assembly CPU {fmt(pct)}
      </span>
    </Tooltip>
  )
}

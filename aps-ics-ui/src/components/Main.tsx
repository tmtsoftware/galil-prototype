/*
 * Main view — the "Component Status, Command and Configuration" screen
 * (SDD §4.3/§4.4). Layout:
 *
 *   ┌────────────┬───────────────────────────────────────────┐
 *   │ ICS        │ [ Status & commanding | Configuration ]    │
 *   │ Components │   commanding: Commands + Lifecycle | Status │
 *   │ selector   │   configuration: read-only config view      │
 *   │            ├───────────────────────────────────────────┤
 *   │            │ Command / Event Log (shared across tabs)    │
 *   └────────────┴───────────────────────────────────────────┘
 *
 * Component-agnostic: the selected key resolves to a ComponentDescriptor
 * (registry.tsx), and all live state is driven off it —
 *   - one CommandService for the selected component (gateway-resolved, AAS auth)
 *     and one AdminService (component-independent; calls take the componentId);
 *   - one EventService subscription to the descriptor's status + axis events, NO
 *     maxFrequency (the gateway RateLimiterMode drops co-published axis events
 *     otherwise); axis events are stored per event name (1 for InsertionStage, 2
 *     for SteeringBeamSplitter);
 *   - a best-effort poll of the CSW supervisor lifecycle state;
 *   - a read of the component's ACTIVE config from the CSW Config Service;
 *   - the Command/Event Log, appended by command + lifecycle traffic.
 * Switching components rebuilds the CommandService, resubscribes, and re-reads
 * lifecycle + config, resetting the displayed state in between.
 */
import * as React from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Layout, Tabs, Typography } from 'antd'
import {
  AdminService,
  CommandService,
  ConfigService,
  EventKey,
  EventName,
  EventService
} from '@tmtsoftware/esw-ts'
import type {
  AdminService as AdminServiceT,
  CommandService as CommandServiceT,
  Event,
  Setup,
  Subscription,
  SubmitResponse,
  SupervisorLifecycleState
} from '@tmtsoftware/esw-ts'
import { useAuth } from '../hooks/useAuth'
import { Login } from './Login'
import { ComponentSelector } from './ComponentSelector'
import { ConfigTab } from './ConfigTab'
import type { ConfigSource } from './ConfigTab'
import { LifecycleCommands } from './LifecycleCommands'
import type { LifecycleName } from './LifecycleCommands'
import { CommandEventLog } from './CommandEventLog'
import { AssemblyCpuBadge } from './AssemblyCpuBadge'
import type { LogEntry, LogLevel } from './CommandEventLog'
import { DEFAULT_KEY, REGISTRY } from './registry'
import { readAxis, readStatus } from '../models/stage'
import type { AxisSnapshot, StatusSnapshot } from '../models/stage'

// queryFinal timeout (s): home can run minutes (HCD home watchdog ~3 min,
// assembly homeHcdTimeout 4 min); 300 s sits safely above the HCD watchdogs.
const FINAL_TIMEOUT_S = 300

const ts = (): string => new Date().toISOString().slice(11, 23) // HH:MM:SS.mmm

// Pull a human-readable detail out of a terminal SubmitResponse, if any.
const detailOf = (res: SubmitResponse): string => {
  const r = res as { message?: string; issue?: { reason?: string } }
  if (r.message) return `: ${r.message}`
  if (r.issue?.reason) return `: ${r.issue.reason}`
  return ''
}

export const Main = (): React.JSX.Element => {
  const { auth } = useAuth()
  const isAuthenticated = auth?.isAuthenticated() ?? false

  const [selected, setSelected] = useState<string>(DEFAULT_KEY)
  const desc = REGISTRY[selected] ?? REGISTRY[DEFAULT_KEY]

  const [commandService, setCommandService] = useState<CommandServiceT>()
  const [adminService, setAdminService] = useState<AdminServiceT>()
  const [status, setStatus] = useState<StatusSnapshot>({})
  const [axes, setAxes] = useState<Record<string, AxisSnapshot>>({})
  const [extras, setExtras] = useState<Record<string, Event>>({})
  const [lifecycle, setLifecycle] = useState<SupervisorLifecycleState>()
  const [busy, setBusy] = useState(false)
  const [liveConfigText, setLiveConfigText] = useState<string>()
  const [configSource, setConfigSource] = useState<ConfigSource>('loading')
  const [log, setLog] = useState<LogEntry[]>([])
  const logId = useRef(0)

  const appendLog = useCallback((level: LogLevel, text: string): void => {
    logId.current += 1
    const entry: LogEntry = { id: logId.current, ts: ts(), level, text }
    setLog((prev) => [...prev, entry].slice(-200)) // keep the last 200 lines
  }, [])

  // AdminService is component-independent (its calls take the componentId), so
  // build it once authenticated.
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let cancelled = false
    AdminService(authData).then((as) => {
      if (!cancelled) setAdminService(as)
    })
    return () => {
      cancelled = true
    }
  }, [auth, isAuthenticated])

  // CommandService is per-component: (re)build it for the selected component.
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let cancelled = false
    setCommandService(undefined)
    CommandService(desc.componentId, authData).then((cs) => {
      if (!cancelled) setCommandService(cs)
    })
    return () => {
      cancelled = true
    }
  }, [auth, isAuthenticated, desc])

  // Subscribe to the descriptor's status + axis events with NO maxFrequency (see
  // the RateLimiter note above). Axis events are stored per event name. Reset the
  // displayed snapshots when switching components.
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let sub: Subscription | undefined
    let cancelled = false
    setStatus({})
    setAxes({})
    setExtras({})
    const extraEvents = desc.extraEvents ?? []
    const names = [desc.statusEvent, ...desc.axisEvents, ...extraEvents]
    const keys = new Set(names.map((n) => new EventKey(desc.prefix, new EventName(n))))
    EventService(authData).then((es) => {
      if (cancelled) return
      sub = es.subscribe(keys)((e: Event) => {
        if (e.eventId === '-1') return
        const name = e.eventName.name
        if (name === desc.statusEvent) setStatus(readStatus(e))
        else if (desc.axisEvents.includes(name)) setAxes((prev) => ({ ...prev, [name]: readAxis(e) }))
        else if (extraEvents.includes(name)) setExtras((prev) => ({ ...prev, [name]: e }))
      })
    })
    return () => {
      cancelled = true
      sub?.cancel()
    }
  }, [auth, isAuthenticated, desc])

  // Best-effort poll of the CSW supervisor lifecycle state for the selected
  // component. If the admin route is unavailable (auth/role), stop quietly.
  useEffect(() => {
    if (!adminService) return
    let cancelled = false
    let timer: ReturnType<typeof setInterval> | undefined
    setLifecycle(undefined)
    const poll = (): void => {
      adminService
        .getComponentLifecycleState(desc.componentId)
        .then((s) => {
          if (!cancelled) setLifecycle(s)
        })
        .catch(() => {
          if (timer) clearInterval(timer)
        })
    }
    poll()
    timer = setInterval(poll, 5000)
    return () => {
      cancelled = true
      if (timer) clearInterval(timer)
    }
  }, [adminService, desc])

  // Read the selected component's ACTIVE config from the CSW Config Service for
  // the Configuration tab. The Config Service resolves directly via the Location
  // Service (not the gateway), so its host:port must be reachable from the
  // browser. If unavailable or the path isn't seeded, fall back to the snapshot.
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    let cancelled = false
    setLiveConfigText(undefined)
    setConfigSource('loading')
    ConfigService(() => auth.token())
      .then(async (cs) => {
        const data = await cs.getActive(desc.configPath)
        if (cancelled) return
        if (data) {
          setLiveConfigText(await data.fileContentAsString())
          setConfigSource('service')
        } else setConfigSource('snapshot')
      })
      .catch(() => {
        if (!cancelled) setConfigSource('snapshot')
      })
    return () => {
      cancelled = true
    }
  }, [auth, isAuthenticated, desc])

  // Submit a Setup (submit -> queryFinal for the terminal result) and log both.
  const run = useCallback(
    async (setup: Setup, label: string): Promise<void> => {
      if (!commandService) return
      setBusy(true)
      appendLog('info', `${desc.label}  ${label}  cmd sent`)
      try {
        let res = await commandService.submit(setup)
        if (res._type === 'Started') res = await commandService.queryFinal(res.runId, FINAL_TIMEOUT_S)
        const ok = res._type === 'Completed'
        appendLog(ok ? 'info' : 'error', `${desc.label}  ${label}  ${res._type}${detailOf(res)}`)
      } catch (e) {
        appendLog('error', `${desc.label}  ${label}  ${(e as Error).message}`)
      } finally {
        setBusy(false)
      }
    },
    [commandService, appendLog, desc]
  )

  // Send a CSW lifecycle command via AdminService and log the outcome.
  const runLifecycle = useCallback(
    async (name: LifecycleName): Promise<void> => {
      if (!adminService) return
      setBusy(true)
      appendLog('info', `${desc.label}  lifecycle ${name}  sent`)
      try {
        const fn = {
          goOnline: () => adminService.goOnline(desc.componentId),
          goOffline: () => adminService.goOffline(desc.componentId),
          restart: () => adminService.restart(desc.componentId),
          shutdown: () => adminService.shutdown(desc.componentId)
        }[name]
        const done = await fn()
        appendLog('info', `${desc.label}  lifecycle ${name}  ${done}`)
      } catch (e) {
        appendLog('error', `${desc.label}  lifecycle ${name}  ${(e as Error).message}`)
      } finally {
        setBusy(false)
      }
    },
    [adminService, appendLog, desc]
  )

  if (!auth) return <div>Loading…</div>
  if (!isAuthenticated) return <Login />

  const ready = commandService !== undefined

  const commandingTab = (
    <div style={{ display: 'flex', gap: '1.5rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
      <div style={{ flex: '1 1 360px', minWidth: 320 }}>
        {desc.renderCommands({ status, ready, busy, run })}
        <LifecycleCommands ready={adminService !== undefined} busy={busy} run={runLifecycle} />
      </div>
      <div style={{ flex: '1 1 300px', minWidth: 280 }}>
        {desc.renderStatus({ status, axes, extras, lifecycle })}
      </div>
    </div>
  )

  return (
    <Layout style={{ minHeight: '100vh', background: 'transparent' }}>
      <div style={{ background: '#2C2C2A', color: '#F1EFE8', padding: '12px 20px', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span>APS engineering &nbsp;·&nbsp; component status, command and configuration</span>
        <AssemblyCpuBadge />
      </div>
      <Layout style={{ background: 'transparent' }}>
        <Layout.Sider width={240} theme='light' style={{ background: 'transparent', borderRight: '1px solid rgba(0,0,0,0.08)' }}>
          <ComponentSelector selectedKey={selected} onSelect={setSelected} />
        </Layout.Sider>
        <Layout.Content style={{ padding: '1rem 1.25rem' }}>
          <Typography.Title level={5} style={{ marginTop: 0 }}>
            {desc.label}
          </Typography.Title>
          <Tabs
            items={[
              { key: 'cmd', label: 'Status & commanding', children: commandingTab },
              {
                key: 'cfg',
                label: 'Configuration',
                children: (
                  <ConfigTab
                    path={desc.configPath}
                    staticView={desc.staticConfig}
                    liveText={liveConfigText}
                    source={configSource}
                  />
                )
              }
            ]}
          />
          {/* Shared across both tabs per SDD §4.3. */}
          <CommandEventLog entries={log} />
        </Layout.Content>
      </Layout>
    </Layout>
  )
}

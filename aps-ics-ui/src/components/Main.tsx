/*
 * Main view for the InsertionStage HMI. Holds the single source of live state:
 *   - one CommandService for the assembly (built once authenticated)
 *   - one EventService subscription to `status` + `axisStatus` (4 Hz)
 * and feeds both the read-only Status panel and the Command panel (which uses the
 * status snapshot for its enable/disable gating).
 *
 * Rendered inside <AuthContextProvider> by App.tsx, so useAuth() has a value.
 */
import * as React from 'react'
import { useEffect, useState } from 'react'
import { CommandService, EventKey, EventName, EventService } from '@tmtsoftware/esw-ts'
import type { CommandService as CommandServiceT, Event, Subscription } from '@tmtsoftware/esw-ts'
import { useAuth } from '../hooks/useAuth'
import { Login } from './Login'
import { InsertionStageCommands } from './InsertionStageCommands'
import { InsertionStageStatus } from './InsertionStageStatus'
import {
  AXIS_EVENT,
  IS_COMPONENT_ID,
  IS_PREFIX,
  STATUS_EVENT,
  readAxis,
  readStatus
} from '../models/insertionStage'
import type { AxisSnapshot, StatusSnapshot } from '../models/insertionStage'

export const Main = (): React.JSX.Element => {
  const { auth } = useAuth()
  const isAuthenticated = auth?.isAuthenticated() ?? false

  const [commandService, setCommandService] = useState<CommandServiceT>()
  const [status, setStatus] = useState<StatusSnapshot>({})
  const [axis, setAxis] = useState<AxisSnapshot>({})

  // Build a CommandService for the assembly once authenticated.
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let cancelled = false
    CommandService(IS_COMPONENT_ID, authData).then((cs) => {
      if (!cancelled) setCommandService(cs)
    })
    return () => {
      cancelled = true
    }
  }, [auth, isAuthenticated])

// Subscribe to status + axisStatus with NO maxFrequency. Supplying a frequency
  // puts the gateway in RateLimiterMode, whose stage runs on the merged key stream
  // and drops whichever of two co-published events arrives second — here axisStatus,
  // emitted microseconds after status in the same publishTelemetry() call. The
  // assembly already throttles its own publish rate, so we take the stream as-is.
  useEffect(() => {
    if (!auth || !isAuthenticated) return
    const authData = { tokenFactory: () => auth.token() }
    let sub: Subscription | undefined
    let cancelled = false
    const keys = new Set([
      new EventKey(IS_PREFIX, new EventName(STATUS_EVENT)),
      new EventKey(IS_PREFIX, new EventName(AXIS_EVENT))
    ])
    EventService(authData).then((es) => {
      if (cancelled) return
      sub = es.subscribe(keys)((e: Event) => {
        if (e.eventId === '-1') return
        if (e.eventName.name === STATUS_EVENT) setStatus(readStatus(e))
        else if (e.eventName.name === AXIS_EVENT) setAxis(readAxis(e))
      })
    })
    return () => {
      cancelled = true
      sub?.cancel()
    }
  }, [auth, isAuthenticated])

  if (!auth) return <div>Loading…</div>
  if (!isAuthenticated) return <Login />

  return (
    <div
      style={{
        display: 'flex',
        gap: '1.5rem',
        placeContent: 'center',
        paddingTop: '2rem',
        flexWrap: 'wrap'
      }}>
      <InsertionStageStatus status={status} axis={axis} />
      <InsertionStageCommands commandService={commandService} status={status} />
    </div>
  )
}
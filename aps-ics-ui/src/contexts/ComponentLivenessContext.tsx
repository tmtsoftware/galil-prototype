/*
 * Component liveness (Location Service tracking).
 *
 * A single source of up/down truth for every REGISTERED component, driven by the
 * CSW Location Service PUSH stream (LocationService.track) rather than polling.
 * For each descriptor we track its Pekko connection; the Location Service emits
 * LocationUpdated when the component registers and LocationRemoved when it goes
 * away (crash, shutdown, restart), so liveness is event-driven with no periodic
 * location resolves.
 *
 * Why this exists: the Event Service retains the last value per key, so a
 * subscription replays a dead component's final status/axis events as if they
 * were live. Location tracking is the only signal that actually says "this
 * component is (not) registered right now".
 *
 * Consumers:
 *   - ComponentSelector renders an up/down dot per node.
 *   - Main badges the selected component, marks its telemetry panel stale,
 *     gates commands, and drives the on-demand SupervisorLifecycleState fetch
 *     (this replaced the former 5 s admin poll).
 *
 * Keyed by descriptor key (= assembly prefix string) — the SAME key space as the
 * registry, the selector tree and Main's `selected`, so lookups line up exactly.
 *
 * Resilience: a track stream that errors (Location Service unreachable, or the
 * browser froze this tab and killed its sockets) is RE-TRACKED with per-key
 * exponential backoff. Before this, a single error event parked the component
 * on 'unknown' (grey) until a full page refresh — observed 2026-08-13 when
 * Chrome froze the backgrounded UI tab under memory pressure and every dot
 * went grey at once.
 */
import React, { createContext, useContext, useEffect, useState } from 'react'
import type { PropsWithChildren } from 'react'
import { PekkoConnection } from '@tmtsoftware/esw-ts'
import type { ComponentId, Subscription, TrackingEvent } from '@tmtsoftware/esw-ts'
import { useLocationService } from './LocationServiceContext'
import { DESCRIPTORS } from '../components/registry'
import { HCD_LIST } from '../components/hcds'

// 'unknown' is the pre-first-event placeholder; track resolves it to up/down
// almost immediately (LocationUpdated for a live component, LocationRemoved for
// an absent one).
export type Liveness = 'up' | 'down' | 'unknown'
export type LivenessMap = Record<string, Liveness>

// Re-track backoff after a track-stream error: start at the floor, double per
// consecutive failure up to the ceiling, reset on the first healthy event.
// Mirrors the HMI's WebSocket reconnect policy (2 s -> 30 s).
const RETRACK_MIN_MS = 2000
const RETRACK_MAX_MS = 30000

const LivenessContext = createContext<LivenessMap>({})

export const ComponentLivenessProvider = ({
  children
}: PropsWithChildren): React.JSX.Element => {
  const locationService = useLocationService()
  const [liveness, setLiveness] = useState<LivenessMap>({})

  useEffect(() => {
    // One live subscription per key (re-track replaces the dead one), plus any
    // pending retry timers and the per-key backoff state.
    const subs = new Map<string, Subscription>()
    const retryTimers = new Map<string, ReturnType<typeof setTimeout>>()
    const retryDelays = new Map<string, number>()
    let cancelled = false

    // Track assemblies and HCDs alike — same Pekko-connection tracking, the
    // componentType (Assembly vs HCD) is carried by each componentId.
    const tracked: { key: string; componentId: ComponentId }[] = [
      ...DESCRIPTORS.map((d) => ({ key: d.key, componentId: d.componentId })),
      ...HCD_LIST.map((h) => ({ key: h.key, componentId: h.componentId }))
    ]

    const trackOne = (t: { key: string; componentId: ComponentId }): void => {
      if (cancelled) return
      const conn = PekkoConnection(t.componentId.prefix, t.componentId.componentType)
      const sub = locationService.track(conn)(
        (e: TrackingEvent) => {
          // Healthy stream: reset this key's backoff to the floor.
          retryDelays.set(t.key, RETRACK_MIN_MS)
          setLiveness((prev) => ({ ...prev, [t.key]: e._type === 'LocationUpdated' ? 'up' : 'down' }))
        },
        () => {
          // Track stream error (Location Service unreachable, or this tab was
          // frozen and its socket died): we can no longer assert up/down, so
          // show 'unknown' — but RE-TRACK with backoff rather than parking
          // there until a page refresh.
          setLiveness((prev) => ({ ...prev, [t.key]: 'unknown' }))
          if (cancelled) return
          const delay = retryDelays.get(t.key) ?? RETRACK_MIN_MS
          retryDelays.set(t.key, Math.min(delay * 2, RETRACK_MAX_MS))
          retryTimers.set(t.key, setTimeout(() => trackOne(t), delay))
        }
      )
      subs.set(t.key, sub)
    }

    tracked.forEach(trackOne)
    return () => {
      cancelled = true
      retryTimers.forEach((timer) => clearTimeout(timer))
      subs.forEach((s) => s.cancel())
    }
  }, [locationService])

  return <LivenessContext.Provider value={liveness}>{children}</LivenessContext.Provider>
}

export const useComponentLiveness = (): LivenessMap => useContext(LivenessContext)

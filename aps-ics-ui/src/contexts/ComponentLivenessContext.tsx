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

const LivenessContext = createContext<LivenessMap>({})

export const ComponentLivenessProvider = ({
  children
}: PropsWithChildren): React.JSX.Element => {
  const locationService = useLocationService()
  const [liveness, setLiveness] = useState<LivenessMap>({})

  useEffect(() => {
    const subs: Subscription[] = []
    // Track assemblies and HCDs alike — same Pekko-connection tracking, the
    // componentType (Assembly vs HCD) is carried by each componentId.
    const tracked: { key: string; componentId: ComponentId }[] = [
      ...DESCRIPTORS.map((d) => ({ key: d.key, componentId: d.componentId })),
      ...HCD_LIST.map((h) => ({ key: h.key, componentId: h.componentId }))
    ]
    tracked.forEach((t) => {
      const conn = PekkoConnection(t.componentId.prefix, t.componentId.componentType)
      const sub = locationService.track(conn)(
        (e: TrackingEvent) => {
          setLiveness((prev) => ({ ...prev, [t.key]: e._type === 'LocationUpdated' ? 'up' : 'down' }))
        },
        () => {
          // track stream error (e.g. Location Service unreachable): we can no
          // longer assert up/down for this component.
          setLiveness((prev) => ({ ...prev, [t.key]: 'unknown' }))
        }
      )
      subs.push(sub)
    })
    return () => subs.forEach((s) => s.cancel())
  }, [locationService])

  return <LivenessContext.Provider value={liveness}>{children}</LivenessContext.Provider>
}

export const useComponentLiveness = (): LivenessMap => useContext(LivenessContext)

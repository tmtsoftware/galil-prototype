/*
 * Liveness indicators — a small dot (selector tree) and a tag (header), both
 * driven by the Location Service tracking state (see
 * contexts/ComponentLivenessContext). Green = registered, red = absent,
 * grey = not yet determined.
 */
import { Tag, Tooltip } from 'antd'
import React from 'react'
import type { Liveness } from '../contexts/ComponentLivenessContext'

const META: Record<Liveness, { color: string; label: string }> = {
  up: { color: '#52c41a', label: 'Online' },
  down: { color: '#ff4d4f', label: 'Offline' },
  unknown: { color: '#bfbfbf', label: 'Unknown' }
}

export const LivenessDot = ({ state }: { state: Liveness }): React.JSX.Element => {
  const m = META[state]
  return (
    <Tooltip title={m.label}>
      <span
        style={{
          display: 'inline-block',
          width: 8,
          height: 8,
          borderRadius: '50%',
          background: m.color,
          marginRight: 6,
          verticalAlign: 'middle'
        }}
      />
    </Tooltip>
  )
}

export const LivenessTag = ({ state }: { state: Liveness }): React.JSX.Element => {
  const m = META[state]
  const color = state === 'up' ? 'green' : state === 'down' ? 'red' : 'default'
  return <Tag color={color}>{m.label}</Tag>
}

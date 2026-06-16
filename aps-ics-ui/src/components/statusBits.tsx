/*
 * Shared presentation helpers for the assembly Status panels (SDD §4.3):
 * a state -> tag-colour map, a numeric formatter, and a boolean tag. Factored
 * out so every stage assembly's Status panel colours states identically.
 */
import { Tag } from 'antd'
import React from 'react'

export const colorFor = (v?: string): string => {
  switch (v) {
    case 'OPERATIONAL':
    case 'READY':
    case 'IDLE':
      return 'green'
    case 'PRE_HOMED':
    case 'UNINITIALIZED':
    case 'HOMING':
    case 'MOVING':
      return 'blue'
    case 'PROCESSING':
    case 'ERROR_RECOVERY':
    case 'DEGRADED':
      return 'orange'
    case 'FAULTED':
    case 'FAILED':
    case 'ERROR':
    case 'LOST':
      return 'red'
    default:
      return 'default'
  }
}

export const fmt = (n?: number, d = 3): string =>
  n === undefined || Number.isNaN(n) ? '—' : n.toFixed(d)

export const BoolTag = ({ b }: { b?: boolean }): React.JSX.Element =>
  b === undefined ? <Tag>—</Tag> : <Tag color={b ? 'green' : 'default'}>{String(b)}</Tag>

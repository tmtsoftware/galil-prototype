/*
 * Shared command-section primitives (the "command kit").
 *
 * Commands are organised into context GROUPS (Setup / Motion / Recovery / ...),
 * with each interrupt sitting in the group it interrupts (Stop with the motion
 * command, Abort with recovery, Stop-loop with Start-loop). Built from:
 *   - CommandGroups — the single stacked column the groups sit in.
 *   - CommandGroup — one titled context section.
 *   - ParamCommand — a parameterised command: a labeled input grid + own Submit.
 *   - Field — one labeled control inside a ParamCommand (control fills its cell).
 *   - Actions / ActionButton — no-parameter commands as a small button cluster.
 * Gating is unchanged: callers pass `enabled` from commandEnabled(...).
 */
import { Button, Typography } from 'antd'
import React from 'react'

const { Text } = Typography

// The groups stack in a SINGLE column (constrained width) rather than a
// responsive grid: a wrapping grid left uncomfortable whitespace at mid widths
// and stretched the cards on wide screens. Stacked + capped keeps every group
// full and its parameters roomy.
export const CommandGroups = ({ children }: { children: React.ReactNode }): React.JSX.Element => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
    {children}
  </div>
)

export const CommandGroup = ({
  title,
  danger,
  children
}: {
  title: string
  danger?: boolean
  children: React.ReactNode
}): React.JSX.Element => (
  <div style={{ border: `1px solid ${danger ? '#ffd8d3' : '#e8e8e8'}`, borderRadius: 10, padding: '10px 12px 12px' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '0 0 9px' }}>
      <Text style={{ fontSize: 11, letterSpacing: '0.06em', textTransform: 'uppercase', color: '#595959', fontWeight: 700 }}>{title}</Text>
      <span style={{ flex: 1, height: 1, background: '#e8e8e8' }} />
    </div>
    {children}
  </div>
)

export const Actions = ({ children }: { children: React.ReactNode }): React.JSX.Element => (
  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>{children}</div>
)

export const ActionButton = ({
  label,
  enabled,
  danger,
  onSubmit
}: {
  label: string
  enabled: boolean
  danger?: boolean
  onSubmit: () => void
}): React.JSX.Element => (
  <Button size='small' danger={danger} disabled={!enabled} onClick={onSubmit}>{label}</Button>
)

// One labeled control. The control child is stretched to fill the grid cell;
// pass `full` to span the whole row (e.g. a lone wide Select).
export const Field = ({
  label,
  full,
  children
}: {
  label: string
  full?: boolean
  children: React.ReactElement
}): React.JSX.Element => {
  const el = children as React.ReactElement<{ style?: React.CSSProperties }>
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0, gridColumn: full ? '1 / -1' : undefined }}>
      <span style={{ fontSize: 10, letterSpacing: '0.02em', color: '#8c8c8c', textTransform: 'uppercase' }}>{label}</span>
      {React.cloneElement(el, { style: { width: '100%', ...(el.props.style || {}) } })}
    </div>
  )
}

export const ParamCommand = ({
  name,
  enabled,
  danger,
  onSubmit,
  children
}: {
  name: string
  enabled: boolean
  danger?: boolean
  onSubmit: () => void
  children: React.ReactNode
}): React.JSX.Element => (
  <div style={{ border: '1px dashed #e8e8e8', borderRadius: 8, padding: '8px 9px', marginBottom: 9, background: '#fcfcfc' }}>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8 }}>
      <Text style={{ fontWeight: 600, fontSize: 13 }}>{name}</Text>
      <Button size='small' type={danger ? 'default' : 'primary'} ghost={!danger} danger={danger} disabled={!enabled} onClick={onSubmit}>Submit</Button>
    </div>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '9px 10px' }}>
      {children}
    </div>
  </div>
)

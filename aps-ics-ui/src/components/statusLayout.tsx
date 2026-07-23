/*
 * Shared status-panel layout primitives (the "status kit").
 *
 * Every assembly Status panel is composed from the same pieces so they read as
 * one system and the old per-file copy-paste (the vertical AxisBlock) is gone:
 *   - AssemblyStateStrip / StateStrip + StateChip — the assembly state as a
 *     compact chip row instead of a 3-row bordered table.
 *   - AxisMatrix — axes as COLUMNS in one compact table (State / Position /
 *     Velocity / Indexed / In position as rows). Replaces N stacked 5-row blocks.
 *   - MetaFooter — config-derived / lifecycle values as a muted footer line.
 * State colours come from statusBits.colorFor; detectors pass their own tags.
 */
import { Tag, Typography } from 'antd'
import React from 'react'
import { colorFor, fmt } from './statusBits'
import type { AxisSnapshot } from '../models/stage'

const { Text } = Typography

// ---- state chips --------------------------------------------------------
export const StateChip = ({
  label,
  children
}: {
  label: string
  children: React.ReactNode
}): React.JSX.Element => (
  <div style={{ border: '1px solid #e8e8e8', borderRadius: 8, padding: '6px 9px', background: '#fff', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, minWidth: 150 }}>
    <span style={{ fontSize: 10, letterSpacing: '0.04em', color: '#8c8c8c', textTransform: 'uppercase' }}>{label}</span>
    {children}
  </div>
)

export const StateStrip = ({ children }: { children: React.ReactNode }): React.JSX.Element => (
  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{children}</div>
)

// Convenience for the common stage-assembly triple (+ optional extra chips,
// e.g. K-Mirror operating mode).
export const AssemblyStateStrip = ({
  status,
  extra
}: {
  status: { assemblyState?: string; hcdState?: string; commandState?: string }
  extra?: React.ReactNode
}): React.JSX.Element => (
  <StateStrip>
    <StateChip label='Assembly'><Tag color={colorFor(status.assemblyState)}>{status.assemblyState ?? '—'}</Tag></StateChip>
    <StateChip label='HCD'><Tag color={colorFor(status.hcdState)}>{status.hcdState ?? '—'}</Tag></StateChip>
    <StateChip label='Command'><Tag color={colorFor(status.commandState)}>{status.commandState ?? '—'}</Tag></StateChip>
    {extra}
  </StateStrip>
)

// ---- boolean glyph ------------------------------------------------------
export const BoolGlyph = ({ b }: { b?: boolean }): React.JSX.Element =>
  b === undefined ? (
    <span style={{ color: '#bfbfbf' }}>—</span>
  ) : b ? (
    <span style={{ color: '#389e0d', fontWeight: 600 }}>✓</span>
  ) : (
    <span style={{ color: '#bfbfbf' }}>✗</span>
  )

// ---- axis matrix (axes as columns, framed) ------------------------------
export type AxisCol = { name: string; unit?: string; axis: AxisSnapshot }
// An extra row appended below the five standard axis rows. `cells` holds one
// node per axis column (so single-axis devices pass a 1-element array).
export type ExtraRow = { label: string; cells: React.ReactNode[] }

const mono: React.CSSProperties = { fontVariantNumeric: 'tabular-nums', fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 13 }
const headCell: React.CSSProperties = { padding: '7px 12px', textAlign: 'right', fontSize: 13, fontWeight: 600, color: '#262626', background: '#fafafa', borderBottom: '1px solid #e8e8e8' }
const labelCell: React.CSSProperties = { padding: '7px 12px', textAlign: 'left', fontSize: 13, fontWeight: 500, color: '#595959', background: '#fafafa', borderRight: '1px solid #f0f0f0' }
const dataCell: React.CSSProperties = { padding: '7px 12px', textAlign: 'right', fontSize: 13 }

export const AxisMatrix = ({
  axes,
  extraRows
}: {
  axes: AxisCol[]
  extraRows?: ExtraRow[]
}): React.JSX.Element => {
  const rows: ExtraRow[] = [
    { label: 'State', cells: axes.map((a) => <Tag color={colorFor(a.axis.axisState)}>{a.axis.axisState ?? '—'}</Tag>) },
    { label: 'Position', cells: axes.map((a) => <span style={mono}>{fmt(a.axis.position)}</span>) },
    { label: 'Velocity', cells: axes.map((a) => <span style={mono}>{fmt(a.axis.velocity)}</span>) },
    { label: 'Indexed', cells: axes.map((a) => <BoolGlyph b={a.axis.indexed} />) },
    { label: 'In position', cells: axes.map((a) => <BoolGlyph b={a.axis.inPosition} />) },
    ...(extraRows ?? [])
  ]
  return (
    <div style={{ border: '1px solid #e8e8e8', borderRadius: 8, overflow: 'hidden' }}>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead>
          <tr>
            <th style={{ ...headCell, textAlign: 'left' }}>Axis</th>
            {axes.map((a) => (
              <th key={a.name} style={headCell}>
                {a.name}
                {a.unit ? <span style={{ color: '#8c8c8c', fontWeight: 400, fontSize: 11 }}> {a.unit}</span> : null}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const last = i === rows.length - 1
            const bb = last ? undefined : '1px solid #f0f0f0'
            return (
              <tr key={r.label}>
                <th style={{ ...labelCell, borderBottom: bb }}>{r.label}</th>
                {r.cells.map((c, j) => (
                  <td key={j} style={{ ...dataCell, borderBottom: bb }}>{c}</td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// ---- meta footer --------------------------------------------------------
export const MetaFooter = ({
  items
}: {
  items: { label: string; value: React.ReactNode }[]
}): React.JSX.Element => (
  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', color: '#8c8c8c', fontSize: 11, borderTop: '1px dashed #e8e8e8', paddingTop: 8, width: '100%' }}>
    {items.map((it) => (
      <span key={it.label}>
        <Text style={{ color: '#595959', fontWeight: 600, fontSize: 11 }}>{it.label}</Text> {it.value}
      </span>
    ))}
  </div>
)

/*
 * Command / Event Log (SDD §4.3, common to the Status&Commanding and the
 * Configuration tabs). Shows command sends and their responses with timestamps.
 *
 * S73 scope: only the "Command / responses" feed is populated — it comes from
 * the UI's own submit/queryFinal traffic (Main appends entries). The "Component
 * warnings" / "Component errors" feeds map to the assembly's apsAlertEvent,
 * which is not published yet, so those filters are disabled for now.
 */
import { Empty, Segmented, Tooltip, Typography } from 'antd'
import React, { useState } from 'react'

export type LogLevel = 'info' | 'error'
export type LogEntry = { id: number; ts: string; level: LogLevel; text: string }

type Filter = 'Command / responses' | 'Component warnings' | 'Component errors'

export const CommandEventLog = ({ entries }: { entries: LogEntry[] }): React.JSX.Element => {
  const [filter, setFilter] = useState<Filter>('Command / responses')

  return (
    <div
      style={{
        border: '1px solid rgba(0,0,0,0.1)',
        borderRadius: 8,
        padding: '0.5rem 0.75rem',
        marginTop: '1rem'
      }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
          COMMAND / EVENT LOG
        </Typography.Text>
        <Tooltip title='Component warnings/errors arrive via the assembly apsAlertEvent (not published yet).'>
          <Segmented<Filter>
            size='small'
            value={filter}
            onChange={(v) => setFilter(v)}
            options={[
              { label: 'Command / responses', value: 'Command / responses' },
              { label: 'Component warnings', value: 'Component warnings', disabled: true },
              { label: 'Component errors', value: 'Component errors', disabled: true }
            ]}
          />
        </Tooltip>
      </div>

      <div
        style={{
          height: 150,
          overflowY: 'auto',
          fontFamily: 'monospace',
          fontSize: 12,
          lineHeight: 1.7,
          background: 'rgba(0,0,0,0.02)',
          borderRadius: 6,
          padding: '6px 8px'
        }}>
        {entries.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description='No commands yet' style={{ marginTop: 28 }} />
        ) : (
          entries.map((e) => (
            <div key={e.id} style={{ color: e.level === 'error' ? '#cf1322' : 'inherit' }}>
              {e.ts}&nbsp;&nbsp;{e.text}
            </div>
          ))
        )}
      </div>
    </div>
  )
}

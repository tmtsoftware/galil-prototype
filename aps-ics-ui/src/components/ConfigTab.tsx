/*
 * Component Configuration tab (SDD §4.4) — generic over the selected component.
 *
 * Read-only: if the assembly's config has been seeded into the CSW Configuration
 * Service, show its live ACTIVE version (raw HOCON) fetched by Main via esw-ts
 * ConfigService.getActive(path). If the service is unavailable or the path isn't
 * present yet, fall back to the component's static structured snapshot.
 *
 * Reset/Update stay disabled — Administrator edit (update + setActiveVersion) is
 * the next step and will turn the raw view into an editable HOCON field.
 */
import { Alert, Button, Descriptions, Space, Typography } from 'antd'
import React from 'react'
import type { ConfigSection } from '../models/stage'

export type ConfigSource = 'service' | 'snapshot' | 'loading'

// Hover hint via the native `title` attribute on the wrapper span rather than
// antd <Tooltip>: these are disabled placeholders for the not-yet-built admin
// edit, and antd's Tooltip (rc-trigger overlay) hangs the React-19 web-test-runner
// render. Native title keeps the hint without that dependency.
const ADMIN_EDIT_HINT = 'Administrator edit (update + setActiveVersion) is the next step.'

const ConfigButtons = (): React.JSX.Element => (
  <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
    <span title={ADMIN_EDIT_HINT}>
      <Button disabled>Reset</Button>
    </span>
    <span title={ADMIN_EDIT_HINT}>
      <Button type='primary' disabled>
        Update
      </Button>
    </span>
  </Space>
)

export const ConfigTab = ({
  path,
  staticView,
  liveText,
  source = 'snapshot'
}: {
  path: string
  staticView: ConfigSection[]
  liveText?: string
  source?: ConfigSource
}): React.JSX.Element => {
  if (source === 'loading') {
    return <Typography.Text type='secondary'>Loading configuration…</Typography.Text>
  }

  // Live active version from the Config Service.
  if (source === 'service' && liveText !== undefined) {
    return (
      <Space direction='vertical' size={12} style={{ width: '100%' }}>
        <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
          ASSEMBLY CONFIGURATION
        </Typography.Text>
        <Alert
          type='success'
          showIcon
          message='Active version from the CSW Configuration Service.'
          description={path}
        />
        <pre
          style={{
            margin: 0,
            padding: '10px 12px',
            border: '1px solid rgba(0,0,0,0.1)',
            borderRadius: 8,
            background: 'rgba(0,0,0,0.02)',
            fontSize: 12,
            lineHeight: 1.6,
            maxHeight: 360,
            overflow: 'auto',
            whiteSpace: 'pre-wrap'
          }}>
          {liveText}
        </pre>
        <ConfigButtons />
      </Space>
    )
  }

  // Fallback: static structured snapshot of the component's .conf.
  return (
    <Space direction='vertical' size={12} style={{ width: '100%' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        ASSEMBLY CONFIGURATION
      </Typography.Text>
      <Alert
        type='info'
        showIcon
        message='Static snapshot of the assembly .conf (not from the Config Service).'
        description={`Seed it into the service at ${path} to show the live active version. Simulator bring-up values, not calibrated.`}
      />
      {staticView.map((section) => (
        <Descriptions key={section.title} title={section.title} column={1} size='small' bordered>
          {section.rows.map((r) => (
            <Descriptions.Item key={r.label} label={r.label}>
              {r.value}
            </Descriptions.Item>
          ))}
        </Descriptions>
      ))}
      <ConfigButtons />
    </Space>
  )
}
/*
 * FocKMirror Status section (SDD §4.3). Pure display of the assembly's PUBLISHED
 * telemetry: the `status` event (assemblyState / hcdState / commandState + the
 * K-Mirror operating mode / slewModeState / trackingModeState, SDD §8.6.5) and the
 * single `axisStatus` event (axisState / position° / velocity / indexed /
 * inPosition).
 *
 * Layout (status kit): the assembly-state chips in a top row with the operating
 * mode as an EXTRA chip, the K-Mirror axis in an AxisMatrix (deg) at a capped
 * width, slewModeState + trackingModeState in a compact extra Descriptions block,
 * and a muted MetaFooter (config-derived HCD label + CSW lifecycle). Phase 1 is
 * MANUAL only, so slewModeState reads NOT_SLEWING and trackingModeState
 * NOT_TRACKING; the rows are present so the tracking-phase telemetry surfaces are
 * already wired. State colouring is shared via ./statusBits (colorFor).
 */
import { Space, Tag, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, AxisMatrix, MetaFooter } from './statusLayout'
import { KM_HCD_LABEL } from '../models/focKMirror'
import type { AxisSnapshot, StatusSnapshot } from '../models/focKMirror'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const modeColor = (s?: string): string =>
  s === 'TRACKING' ? 'green' : s === 'SLEWING' ? 'blue' : 'default'

export const FocKMirrorStatus = ({
  status,
  axis,
  lifecycle
}: {
  status: StatusSnapshot
  axis: AxisSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={10} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <AssemblyStateStrip status={status} />
    <div style={{ maxWidth: 360 }}>
      <AxisMatrix axes={[{ name: 'K-Mirror', unit: 'deg', axis }]}
        extraRows={[
          { label: 'Mode', cells: [<Tag key='mode' color={modeColor(status.mode)}>{status.mode ?? '—'}</Tag>] },
          { label: 'Slew mode', cells: [status.slewModeState ?? '—'] },
          { label: 'Tracking mode', cells: [status.trackingModeState ?? '—'] }
        ]}
      />
    </div>
    <MetaFooter
      items={[
        { label: 'HCD', value: <>{KM_HCD_LABEL} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(config)</Typography.Text></> },
        { label: 'Lifecycle', value: lifecycle ?? '—' }
      ]}
    />
  </Space>
)

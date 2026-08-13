/*
 * ABE Shutter Status section. Pure display of the assembly's PUBLISHED `status`
 * event: the common state triple (assemblyState / hcdState / commandState) plus
 * the shutter-specific fields (shutterBladeAState / shutterBladeBState /
 * shutterErrorIndicator). Layout (status kit): assembly-state chips with the
 * blade states and error indicator as extra chips, and a muted MetaFooter.
 * The mock binds no HCD, so the HCD chip shows the assembly's by-fiat READY.
 */
import { Space, Tag, Typography } from 'antd'
import React from 'react'
import { AssemblyStateStrip, MetaFooter, StateChip } from './statusLayout'
import type { StatusSnapshot } from '../models/abeShutter'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const bladeColor = (v?: string): string => (v === 'OPEN' ? 'green' : v === 'CLOSED' ? 'default' : 'default')

export const AbeShutterStatus = ({
  status,
  lifecycle
}: {
  status: StatusSnapshot
  lifecycle?: SupervisorLifecycleState
}): React.JSX.Element => (
  <Space direction='vertical' size={10} style={{ width: '100%' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
      ASSEMBLY STATUS
    </Typography.Text>
    <AssemblyStateStrip
      status={status}
      extra={
        <>
          <StateChip label='Blade A'>
            <Tag color={bladeColor(status.shutterBladeAState)}>{status.shutterBladeAState ?? '—'}</Tag>
          </StateChip>
          <StateChip label='Blade B'>
            <Tag color={bladeColor(status.shutterBladeBState)}>{status.shutterBladeBState ?? '—'}</Tag>
          </StateChip>
          <StateChip label='Shutter error'>
            <Tag color={status.shutterErrorIndicator === 'YES' ? 'red' : 'default'}>
              {status.shutterErrorIndicator ?? '—'}
            </Tag>
          </StateChip>
        </>
      }
    />
    <MetaFooter
      items={[
        { label: 'Actuation', value: <>MOCK — self-contained, no HCD <Typography.Text type='secondary' style={{ fontSize: 11 }}>(setBit when output map is defined)</Typography.Text></> },
        { label: 'Lifecycle', value: <>{lifecycle ?? '—'} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></> }
      ]}
    />
  </Space>
)

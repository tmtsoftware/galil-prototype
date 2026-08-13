/*
 * ABE Enclosure Status section. Pure display of the assembly's PUBLISHED
 * `status` event: the common state triple, the valve-derived flow/pressure
 * telemetry, the coolant-leak sensor block and the bench environment block.
 * Layout (status kit): assembly-state chips on top, then three compact
 * Descriptions tables, and a muted MetaFooter. The mock binds no HCD, so the
 * HCD chip shows the assembly's by-fiat READY; the environment values are the
 * mock's static plausibles.
 */
import { Descriptions, Space, Tag, Typography } from 'antd'
import React from 'react'
import { fmt } from './statusBits'
import { AssemblyStateStrip, MetaFooter } from './statusLayout'
import type { StatusSnapshot } from '../models/abeEnclosure'
import type { SupervisorLifecycleState } from '@tmtsoftware/esw-ts'

const leakTag = (v?: string): React.JSX.Element => (
  <Tag color={v === 'LEAK_DETECTED' ? 'red' : v === 'NONE' ? 'green' : 'default'}>{v ?? '—'}</Tag>
)
const sensorTag = (v?: string): React.JSX.Element => (
  <Tag color={v === 'FAULTED' ? 'red' : v === 'READY' ? 'green' : 'default'}>{v ?? '—'}</Tag>
)

// One environment station as "temp · RH · dew point".
const env = (t?: number, h?: number, d?: number): string =>
  `${fmt(t, 1)} degC · ${fmt(h, 1)} %RH · ${fmt(d, 1)} degC dew`

export const AbeEnclosureStatus = ({
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
    <AssemblyStateStrip status={status} />

    <Descriptions title='Purge air & coolant' column={1} size='small' bordered style={{ maxWidth: 520 }}>
      <Descriptions.Item label='Purge air flow (l/s)'>{fmt(status.purgeAirFlowRate, 1)}</Descriptions.Item>
      <Descriptions.Item label='Coolant pressure (bar)'>{fmt(status.coolantPressure, 1)}</Descriptions.Item>
      <Descriptions.Item label='Coolant flow PSH / PIT / APT / LOWFS (l/s)'>
        {fmt(status.pshCoolantFlowRate, 1)} / {fmt(status.pitCoolantFlowRate, 1)} /{' '}
        {fmt(status.aptCoolantFlowRate, 1)} / {fmt(status.lowfsCoolantFlowRate, 1)}
      </Descriptions.Item>
    </Descriptions>

    <Descriptions title='Coolant leak detection' column={1} size='small' bordered style={{ maxWidth: 520 }}>
      <Descriptions.Item label='Bench'>
        {leakTag(status.benchCoolantLeakSensorDetectionState)} sensor {sensorTag(status.benchCoolantLeakSensorFaultDetection)}
      </Descriptions.Item>
      <Descriptions.Item label='Rack to bench'>
        {leakTag(status.rackToBenchCoolantLeakSensorDetectionState)} sensor {sensorTag(status.rackToBenchCoolantLeakSensorFaultDetection)}
      </Descriptions.Item>
      <Descriptions.Item label='Rack'>
        {leakTag(status.rackCoolantLeakSensorDetectionState)} sensor {sensorTag(status.rackCoolantLeakSensorFaultDetection)}
      </Descriptions.Item>
    </Descriptions>

    <Descriptions title='Environment' column={1} size='small' bordered style={{ maxWidth: 520 }}>
      <Descriptions.Item label='Shutter'>
        {env(status.shutterTemperature, status.shutterHumidity, status.shutterDewPoint)}
      </Descriptions.Item>
      <Descriptions.Item label='PSH detector'>
        {env(status.pshTemperature, status.pshHumidity, status.pshDewPoint)}
      </Descriptions.Item>
      <Descriptions.Item label='Electronics cabinet'>
        {env(status.electronicsCabinetTemperature, status.electronicsCabinetHumidity, status.electronicsCabinetDewPoint)}
      </Descriptions.Item>
      <Descriptions.Item label='Bench (placeholder sensor)'>
        {fmt(status.placeholderCurrentTemperature, 1)} degC
      </Descriptions.Item>
    </Descriptions>

    <MetaFooter
      items={[
        { label: 'Actuation', value: <>MOCK — self-contained, no HCD <Typography.Text type='secondary' style={{ fontSize: 11 }}>(setBit when output map is defined)</Typography.Text></> },
        { label: 'Lifecycle', value: <>{lifecycle ?? '—'} <Typography.Text type='secondary' style={{ fontSize: 11 }}>(CSW)</Typography.Text></> }
      ]}
    />
  </Space>
)

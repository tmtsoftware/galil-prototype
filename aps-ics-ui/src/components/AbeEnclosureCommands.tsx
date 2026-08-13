/*
 * ABE Enclosure Commands section. One context group with the two ICD valve
 * commands, commandPurgeAir and commandCoolantControlValve (both ON/OFF), as
 * ParamCommands. Gating mirrors the assembly's validate gate via
 * commandEnabled; everything is disabled while a command is in flight (`busy`).
 * The submit + result logging lives in Main via `run`.
 */
import { Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import {
  VALVE_ACTIONS,
  commandCoolantControlValveCmd,
  commandEnabled,
  commandPurgeAirCmd
} from '../models/abeEnclosure'
import type { StatusSnapshot, ValveAction } from '../models/abeEnclosure'

export const AbeEnclosureCommands = ({
  status,
  ready,
  busy,
  run
}: {
  status: StatusSnapshot
  ready: boolean
  busy: boolean
  run: (setup: Setup, label: string) => void
}): React.JSX.Element => {
  const [purge, setPurge] = useState<ValveAction>('OFF')
  const [coolant, setCoolant] = useState<ValveAction>('OFF')

  const enabled = commandEnabled(status, ready, busy)

  return (
    <Space direction='vertical' size={8} style={{ width: '100%' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        ASSEMBLY COMMANDS
      </Typography.Text>
      <CommandGroups>
        <CommandGroup title='Enclosure valves'>
          <ParamCommand
            name='Purge air'
            enabled={enabled}
            onSubmit={() => run(commandPurgeAirCmd(purge), `commandPurgeAir [action=${purge}]`)}>
            <Field label='Action'>
              <Select<ValveAction>
                size='small'
                value={purge}
                onChange={setPurge}
                options={VALVE_ACTIONS.map((a) => ({ value: a, label: a }))}
              />
            </Field>
          </ParamCommand>
          <ParamCommand
            name='Coolant control valve'
            enabled={enabled}
            onSubmit={() => run(commandCoolantControlValveCmd(coolant), `commandCoolantControlValve [action=${coolant}]`)}>
            <Field label='Action'>
              <Select<ValveAction>
                size='small'
                value={coolant}
                onChange={setCoolant}
                options={VALVE_ACTIONS.map((a) => ({ value: a, label: a }))}
              />
            </Field>
          </ParamCommand>
        </CommandGroup>
      </CommandGroups>
    </Space>
  )
}

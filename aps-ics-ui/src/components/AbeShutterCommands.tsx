/*
 * ABE Shutter Commands section. One context group with the single ICD command,
 * commandShutter (OPEN/CLOSE), as a ParamCommand. Gating mirrors the assembly's
 * validate gate via commandEnabled; everything is disabled while a command is
 * in flight (`busy`). The submit + result logging lives in Main via `run`.
 */
import { Select, Space, Typography } from 'antd'
import type { Setup } from '@tmtsoftware/esw-ts'
import React, { useState } from 'react'
import { CommandGroup, CommandGroups, Field, ParamCommand } from './commandKit'
import { SHUTTER_COMMANDS, commandEnabled, commandShutterCmd } from '../models/abeShutter'
import type { ShutterCommand, StatusSnapshot } from '../models/abeShutter'

export const AbeShutterCommands = ({
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
  const [command, setCommand] = useState<ShutterCommand>('CLOSE')

  return (
    <Space direction='vertical' size={8} style={{ width: '100%' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        ASSEMBLY COMMANDS
      </Typography.Text>
      <CommandGroups>
        <CommandGroup title='Shutter'>
          <ParamCommand
            name='Command shutter'
            enabled={commandEnabled(status, ready, busy)}
            onSubmit={() => run(commandShutterCmd(command), `commandShutter [command=${command}]`)}>
            <Field label='Command'>
              <Select<ShutterCommand>
                size='small'
                value={command}
                onChange={setCommand}
                options={SHUTTER_COMMANDS.map((c) => ({ value: c, label: c }))}
              />
            </Field>
          </ParamCommand>
        </CommandGroup>
      </CommandGroups>
    </Space>
  )
}

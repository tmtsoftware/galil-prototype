/*
 * Component Lifecycle Command section (SDD §4.3). Sends CSW lifecycle commands
 * to the selected component through esw-ts AdminService (gateway-resolved, same
 * AAS auth as command submission).
 *
 * esw-ts AdminService exposes goOnline / goOffline / restart / shutdown only.
 * Lock / Unlock are NOT command verbs in esw-ts (Lock is a SupervisorLifecycle
 * STATE, not an admin command), so those two buttons are present-but-disabled to
 * keep the SDD layout while being honest about the available API. Restart and
 * Shutdown are confirmed before sending.
 */
import { Button, Popconfirm, Space, Tooltip, Typography } from 'antd'
import React from 'react'

export type LifecycleName = 'goOnline' | 'goOffline' | 'restart' | 'shutdown'

export const LifecycleCommands = ({
  ready,
  busy,
  run
}: {
  ready: boolean
  busy: boolean
  run: (name: LifecycleName) => void
}): React.JSX.Element => {
  const disabled = !ready || busy

  return (
    <Space direction='vertical' size={6} style={{ width: '100%', marginTop: '0.75rem' }}>
      <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em' }}>
        LIFECYCLE COMMANDS
      </Typography.Text>
      <Space wrap>
        <Button size='small' disabled={disabled} onClick={() => run('goOnline')}>
          Go online
        </Button>
        <Button size='small' disabled={disabled} onClick={() => run('goOffline')}>
          Go offline
        </Button>
        <Popconfirm title='Restart the component?' onConfirm={() => run('restart')} okText='Restart' disabled={disabled}>
          <Button size='small' disabled={disabled}>
            Restart
          </Button>
        </Popconfirm>
        <Popconfirm title='Shut the component down?' onConfirm={() => run('shutdown')} okText='Shutdown' okButtonProps={{ danger: true }} disabled={disabled}>
          <Button size='small' danger disabled={disabled}>
            Shutdown
          </Button>
        </Popconfirm>
        <Tooltip title='Lock/Unlock is not exposed by the ESW gateway AdminService.'>
          <span>
            <Button size='small' disabled>
              Lock
            </Button>
          </span>
        </Tooltip>
        <Tooltip title='Lock/Unlock is not exposed by the ESW gateway AdminService.'>
          <span>
            <Button size='small' disabled>
              Unlock
            </Button>
          </span>
        </Tooltip>
      </Space>
    </Space>
  )
}

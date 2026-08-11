/*
 * Login gate: triggers the esw-ts/AAS redirect to Keycloak. Rendered by Main
 * whenever the user is not yet authenticated. (Use osw-user1 / osw-user1 in the
 * csw-services test realm — it holds the aps-user role.)
 */
import React, { useEffect } from 'react'
import { useAuth } from '../hooks/useAuth'

export const Login = (): React.JSX.Element => {
  const { login } = useAuth()
  useEffect(login, [login])
  return <div>Redirecting to login…</div>
}
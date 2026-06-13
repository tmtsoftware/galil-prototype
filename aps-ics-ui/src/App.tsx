import React from 'react'
import { AuthContextProvider, LocationService, loadGlobalConfig } from '@tmtsoftware/esw-ts'
import { BrowserRouter as Router } from 'react-router-dom'
import { AppConfig } from './config/AppConfig'
import { LocationServiceProvider } from './contexts/LocationServiceContext'
import { useQuery } from './hooks/useQuery'
import { Main } from './components/Main'

// import.meta.env.PROD is a boolean; only prefix the basename in a production build.
const basename = import.meta.env.PROD ? `/${AppConfig.applicationName}` : ''

export const App = (): React.JSX.Element => {
  const { data: initialised, error } = useQuery(() => loadGlobalConfig().then(() => true))
  const locationService = LocationService()

  if (error) return <div> Failed to load global config </div>
  return initialised ? (
    <LocationServiceProvider locationService={locationService}>
      <Router basename={basename}>
        {/* AuthContextProvider (default realm=TMT, client=tmt-frontend-app) makes
            useAuth() available to Main and runs Keycloak check-sso on load. */}
        <AuthContextProvider>
          <Main />
        </AuthContextProvider>
      </Router>
    </LocationServiceProvider>
  ) : (
    <div>Loading....</div>
  )
}

export default App
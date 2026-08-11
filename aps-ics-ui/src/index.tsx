import '@ant-design/v5-patch-for-react-19'
import { setAppName } from '@tmtsoftware/esw-ts'
import ReactDOM from 'react-dom/client'
import React from 'react'
import { App } from './App'
import { AppConfig } from './config/AppConfig'
import './index.css'

setAppName(AppConfig.applicationName)

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement)
root.render(<App />)

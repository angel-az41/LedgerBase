import React from 'react'
import ReactDOM from 'react-dom/client'

import AppGate from './AppGate'
import { AuthProvider } from './auth/AuthProvider'

import './index.css'

ReactDOM.createRoot(
  document.getElementById('root')!,
).render(
  <React.StrictMode>
    <AuthProvider>
      <AppGate />
    </AuthProvider>
  </React.StrictMode>,
)

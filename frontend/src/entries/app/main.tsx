import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { AppChannelApp } from './AppChannelApp.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppChannelApp />
  </StrictMode>,
)

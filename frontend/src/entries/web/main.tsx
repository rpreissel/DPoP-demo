import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { WebChannelApp } from './WebChannelApp.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <WebChannelApp />
  </StrictMode>,
)

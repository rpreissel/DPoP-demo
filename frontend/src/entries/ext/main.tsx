import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { ExtApp } from './ExtApp.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ExtApp />
  </StrictMode>,
)

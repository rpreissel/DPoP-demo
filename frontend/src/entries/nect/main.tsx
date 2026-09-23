import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { NectApp } from './NectApp.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <NectApp />
  </StrictMode>,
)

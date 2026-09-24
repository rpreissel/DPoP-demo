import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { REGISTER_TEXTS, loadAllTexts } from '../../texts'
import { ExtApp } from './ExtApp.tsx'

// The backend sends text references only; their wordings must be here before the first render.
void loadAllTexts(REGISTER_TEXTS).then(() =>
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <ExtApp />
    </StrictMode>,
  ),
)

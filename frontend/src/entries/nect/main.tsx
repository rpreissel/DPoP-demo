import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { NECT_TEXTS, REGISTER_TEXTS, loadAllTexts } from '../../texts'
import { NectApp } from './NectApp.tsx'

// The backend sends text references only; their wordings must be here before the first render.
void loadAllTexts(NECT_TEXTS, REGISTER_TEXTS).then(() =>
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <NectApp />
    </StrictMode>,
  ),
)

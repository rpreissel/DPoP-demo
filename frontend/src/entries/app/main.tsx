import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, KOBIL_TEXTS, loadAllTexts } from '../../texts'
import { AppChannelApp } from './AppChannelApp.tsx'

// The backend sends text references only; their wordings must be here before the first render.
void loadAllTexts(APP_TEXTS, KOBIL_TEXTS).then(() =>
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <AppChannelApp />
    </StrictMode>,
  ),
)

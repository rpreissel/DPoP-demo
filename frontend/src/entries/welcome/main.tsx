import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, loadAllTexts } from '../../texts'
import { WelcomeApp } from './WelcomeApp.tsx'

// The backend sends text references only; their wordings must be here before the first render.
void loadAllTexts(APP_TEXTS).then(() =>
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <WelcomeApp />
    </StrictMode>,
  ),
)

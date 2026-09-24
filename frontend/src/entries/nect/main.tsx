import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, NECT_TEXTS, REGISTER_TEXTS, loadAllTexts } from '../../texts'

// Texts first, then the app: the backend sends text references only, and the app's own t("...")
// calls - module-level ones included (tool labels) - must find their wordings when they run.
void loadAllTexts(NECT_TEXTS, REGISTER_TEXTS, APP_TEXTS)
  .then(() => import('./NectApp.tsx'))
  .then(({ NectApp }) =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <NectApp />
      </StrictMode>,
    ),
  )

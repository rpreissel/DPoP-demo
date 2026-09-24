import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, PERSONENVERZEICHNIS_TEXTS, loadAllTexts } from '../../texts'

// Texts first, then the app: the backend sends text references only, and the app's own t("...")
// calls - module-level ones included (tool labels) - must find their wordings when they run.
void loadAllTexts(PERSONENVERZEICHNIS_TEXTS, APP_TEXTS)
  .then(() => import('./PersonenverzeichnisApp.tsx'))
  .then(({ PersonenverzeichnisApp }) =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <PersonenverzeichnisApp />
      </StrictMode>,
    ),
  )

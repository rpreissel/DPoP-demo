import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, KOBIL_TEXTS, loadAllTexts } from '../../texts'

// Texts first, then the app: the backend sends text references only, and the app's own t("...")
// calls - module-level ones included (tool labels) - must find their wordings when they run.
void loadAllTexts(APP_TEXTS, KOBIL_TEXTS)
  .then(() => import('./AppChannelApp.tsx'))
  .then(({ AppChannelApp }) =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <AppChannelApp />
      </StrictMode>,
    ),
  )

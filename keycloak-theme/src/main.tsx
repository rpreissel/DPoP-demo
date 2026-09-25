import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import type { KcContext } from './login/KcContext'
import KcPage from './login/KcPage'

declare global {
  interface Window {
    kcContext?: KcContext
  }
}

// Keycloak puts kcContext on the page; `npm run dev` without Keycloak shows a mocked page instead
// (imported only then, so the mock and its message files stay out of the theme).
const kcContext = window.kcContext ?? (import.meta.env.DEV ? (await import('./login/mockContext')).mockContext() : undefined)

createRoot(document.getElementById('root')!).render(
  <StrictMode>{kcContext ? <KcPage kcContext={kcContext} /> : <h1>No kcContext</h1>}</StrictMode>,
)

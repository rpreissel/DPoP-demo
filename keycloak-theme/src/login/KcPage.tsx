import { lazy, Suspense } from 'react'
import DefaultPage from 'keycloakify/login/DefaultPage'
import Template from 'keycloakify/login/Template'
import type { KcContext } from './KcContext'
import { useI18n } from './i18n'
import { setLanguage } from '../texts'
import { OrchestratorSelect } from './pages/OrchestratorSelect'
import { OrchestratorTool } from './pages/OrchestratorTool'
import './theme.css'

const UserProfileFormFields = lazy(() => import('keycloakify/login/UserProfileFormFields'))

/**
 * One component per page id. The orchestrator's own pages are rebuilt here; any page id without
 * its own component - Keycloak's built-in pages such as login-page-expired.ftl - falls back to
 * Keycloakify's default rendering.
 */
export default function KcPage({ kcContext }: { kcContext: KcContext }) {
  setLanguage(kcContext.locale?.currentLanguageTag)
  const { i18n } = useI18n({ kcContext })
  return (
    <Suspense>
      {(() => {
        switch (kcContext.pageId) {
          case 'orchestrator-select.ftl':
            return <OrchestratorSelect kcContext={kcContext} />
          case 'orchestrator-tool.ftl':
            return <OrchestratorTool kcContext={kcContext} />
          default:
            return (
              <DefaultPage
                kcContext={kcContext}
                i18n={i18n}
                classes={{}}
                Template={Template}
                doUseDefaultCss={true}
                UserProfileFormFields={UserProfileFormFields}
                doMakeUserConfirmPassword={true}
              />
            )
        }
      })()}
    </Suspense>
  )
}

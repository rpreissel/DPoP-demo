import { lazy, Suspense } from 'react'
import DefaultPage from 'keycloakify/login/DefaultPage'
import Template from 'keycloakify/login/Template'
import type { KcContext } from './KcContext'
import { useI18n } from './i18n'
import { setTexts } from '../texts'
import { OrchestratorConfirm } from './pages/OrchestratorConfirm'
import { OrchestratorError } from './pages/OrchestratorError'
import { OrchestratorManageMethods } from './pages/OrchestratorManageMethods'
import { OrchestratorSelect } from './pages/OrchestratorSelect'
import { OrchestratorTool } from './pages/OrchestratorTool'
import { ToolEmailAuth } from './pages/ToolEmailAuth'
import { ToolEmailEnroll } from './pages/ToolEmailEnroll'
import { ToolEmailLookup } from './pages/ToolEmailLookup'
import { ToolIdentEid } from './pages/ToolIdentEid'
import { ToolIdentFsc } from './pages/ToolIdentFsc'
import { ToolIdentKvnr } from './pages/ToolIdentKvnr'
import { ToolPasswordAuth } from './pages/ToolPasswordAuth'
import { ToolPasswordEnroll } from './pages/ToolPasswordEnroll'
import { ToolPasswordLookup } from './pages/ToolPasswordLookup'
import { ToolQrEnroll } from './pages/ToolQrEnroll'
import { ToolQrWait } from './pages/ToolQrWait'
import { ToolSmsAuth } from './pages/ToolSmsAuth'
import { ToolSmsEnroll } from './pages/ToolSmsEnroll'
import { ToolSmsLookup } from './pages/ToolSmsLookup'
import './theme.css'

const UserProfileFormFields = lazy(() => import('keycloakify/login/UserProfileFormFields'))

/**
 * One component per page id. The orchestrator's own pages are rebuilt here; any page id without
 * its own component - Keycloak's built-in pages such as login-page-expired.ftl - falls back to
 * Keycloakify's default rendering.
 */
export default function KcPage({ kcContext }: { kcContext: KcContext }) {
  setTexts(kcContext.texts)
  const { i18n } = useI18n({ kcContext })
  return (
    <Suspense>
      {(() => {
        switch (kcContext.pageId) {
          case 'orchestrator-confirm.ftl':
            return <OrchestratorConfirm kcContext={kcContext} />
          case 'orchestrator-error.ftl':
            return <OrchestratorError kcContext={kcContext} />
          case 'orchestrator-manage-methods.ftl':
            return <OrchestratorManageMethods kcContext={kcContext} />
          case 'orchestrator-select.ftl':
            return <OrchestratorSelect kcContext={kcContext} />
          case 'orchestrator-tool.ftl':
            return <OrchestratorTool kcContext={kcContext} />
          case 'tool-email-auth.ftl':
            return <ToolEmailAuth kcContext={kcContext} />
          case 'tool-email-enroll.ftl':
            return <ToolEmailEnroll kcContext={kcContext} />
          case 'tool-email-lookup.ftl':
            return <ToolEmailLookup kcContext={kcContext} />
          case 'tool-ident-eid.ftl':
            return <ToolIdentEid kcContext={kcContext} />
          case 'tool-ident-fsc.ftl':
            return <ToolIdentFsc kcContext={kcContext} />
          case 'tool-ident-kvnr.ftl':
            return <ToolIdentKvnr kcContext={kcContext} />
          case 'tool-password-auth.ftl':
            return <ToolPasswordAuth kcContext={kcContext} />
          case 'tool-password-enroll.ftl':
            return <ToolPasswordEnroll kcContext={kcContext} />
          case 'tool-password-lookup.ftl':
            return <ToolPasswordLookup kcContext={kcContext} />
          case 'tool-qr-enroll.ftl':
            return <ToolQrEnroll kcContext={kcContext} />
          case 'tool-qr-wait.ftl':
            return <ToolQrWait kcContext={kcContext} />
          case 'tool-sms-auth.ftl':
            return <ToolSmsAuth kcContext={kcContext} />
          case 'tool-sms-enroll.ftl':
            return <ToolSmsEnroll kcContext={kcContext} />
          case 'tool-sms-lookup.ftl':
            return <ToolSmsLookup kcContext={kcContext} />
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

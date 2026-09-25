import type { KcContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/** `orchestrator-error.ftl`: the message Keycloak carries (kcContext.message) under a fixed title. */
export function OrchestratorError({ kcContext }: { kcContext: Extract<KcContext, { pageId: 'orchestrator-error.ftl' }> }) {
  return <Layout kcContext={kcContext} title={t('Anmeldung nicht möglich')}>{null}</Layout>
}

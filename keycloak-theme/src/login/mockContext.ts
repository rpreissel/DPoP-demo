import { createGetKcContextMock } from 'keycloakify/login/KcContext'
import type { KcContext, KcContextExtension, KcContextExtensionPerPage } from './KcContext'

/**
 * Pages without Keycloak, for `npm run dev` and tests: `?page=orchestrator-tool.ftl` picks the
 * page, the realm is the demo's own. Values mirror what WebFormRenderer sets.
 */
const { getKcContextMock } = createGetKcContextMock({
  kcContextExtension: { themeName: 'orchestrator-keycloakify', properties: {} } satisfies KcContextExtension,
  kcContextExtensionPerPage: {} as KcContextExtensionPerPage,
  overrides: { realm: { name: 'Demo', displayName: 'Demo' } },
  overridesPerPage: {
    'orchestrator-select.ftl': {
      title: 'Anmeldung bei Demo',
      options: ['auth-qr-lookup', 'auth-sms-lookup', 'auth-password-lookup'],
      optionLabels: { 'auth-qr-lookup': 'Mit der App anmelden', 'auth-sms-lookup': 'SMS', 'auth-password-lookup': 'Passwort' },
      offerRegistration: true,
    },
    'orchestrator-tool.ftl': {
      toolId: 'auth-example',
      fields: { email: '', code: '' },
    },
  },
})

export function mockContext(): KcContext {
  const pageId = new URLSearchParams(window.location.search).get('page') ?? 'orchestrator-select.ftl'
  return getKcContextMock({ pageId: pageId as KcContext['pageId'] }) as KcContext
}

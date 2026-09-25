import { createGetKcContextMock } from 'keycloakify/login/KcContext'
import type { KcContext, KcContextExtension, KcContextExtensionPerPage } from './KcContext'
import { parseProperties } from '../texts'
import de from '../../../keycloak-extension/src/main/resources/theme/orchestrator/login/messages/messages_de.properties?raw'
import en from '../../../keycloak-extension/src/main/resources/theme/orchestrator/login/messages/messages_en.properties?raw'

/**
 * Pages without Keycloak, for `npm run dev` and tests: `?page=orchestrator-tool.ftl` picks the
 * page, `?lang=en` the language, the realm is the demo's own. Values mirror what WebFormRenderer
 * sets - `texts` read straight from the FreeMarker theme's messages, as Keycloak would.
 */
const { getKcContextMock } = createGetKcContextMock({
  kcContextExtension: { themeName: 'orchestrator-keycloakify', properties: {} } as KcContextExtension,
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
  const query = new URLSearchParams(window.location.search)
  const pageId = query.get('page') ?? 'orchestrator-select.ftl'
  const texts = parseProperties(query.get('lang') === 'en' ? en : de)
  return getKcContextMock({ pageId: pageId as KcContext['pageId'], overrides: { texts } }) as KcContext
}

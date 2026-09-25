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
const PERSONS =
  '[{"vorname":"Erika","name":"Mustermann","email":"erika@example.org","kvnr":"A123456780","personId":"P000000001","geburtsdatum":"1964-08-12","strasse":"Heidestraße 17","plz":"51147","ort":"Köln","fscCode":"ABCD-1234"}]'

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
    'orchestrator-confirm.ftl': {
      title: 'Konto wirklich löschen?',
      confirmLabel: 'Ja, löschen',
      cancelLabel: 'Nein, behalten',
    },
    'orchestrator-error.ftl': {
      message: { type: 'error', summary: 'Die Anmeldung ist gerade nicht möglich.' },
    },
    'tool-password-lookup.ftl': { toolId: 'auth-password-lookup', title: 'Passwort', hint: 'E-Mail-Adresse und Passwort', demoEmail: 'erika@example.org', demoPassword: 'demo1234', demoPersonsJson: PERSONS },
    'tool-sms-enroll.ftl': { toolId: 'enroll-sms', title: 'SMS', hint: 'Code per SMS', step: 'tanInput', demoTan: '123456' },
    'tool-ident-fsc.ftl': { toolId: 'ident-fsc', title: 'Freischaltcode', personalienPage: true, demoPersonsJson: PERSONS },
    'tool-ident-eid.ftl': { toolId: 'ident-eid', title: 'Online-Ausweis', hint: 'Mit dem Personalausweis', step: 'card', demoPersonsJson: PERSONS },
    'tool-qr-wait.ftl': {
      toolId: 'auth-qr-lookup',
      title: 'Mit der App anmelden',
      pairingCode: 'K7Q2-M9XD',
      verificationCode: '47',
      deepLink: 'http://localhost:8080/app/?intent=confirm_peer_login&pairingCode=K7Q2-M9XD',
      qrDataUri: 'data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 10 10%22><rect width=%2210%22 height=%2210%22 fill=%22%23eee%22/><rect x=%221%22 y=%221%22 width=%223%22 height=%223%22/><rect x=%226%22 y=%221%22 width=%223%22 height=%223%22/><rect x=%221%22 y=%226%22 width=%223%22 height=%223%22/></svg>',
    },
    'orchestrator-manage-methods.ftl': {
      methods: [
        { id: '1', method: 'PASSWORD', label: 'Passwort' },
        { id: '2', method: 'SMS', label: 'SMS an +49 151 ••• 67' },
      ],
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

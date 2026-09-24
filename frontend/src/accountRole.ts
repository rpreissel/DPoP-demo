import { t } from './texts'

/**
 * The role of an account (ADR-34), from the two identifiers its claims carry: a Versicherungsnummer
 * makes a Versicherter, a person without one a Partner, no person at all an Interessent. The same
 * rule for the app's ID claims (`personId`/`versnr`) and Keycloak's tokens (`person_id`/`versnr`).
 */
export function accountRole(personId: unknown, versnr: unknown): string {
  if (versnr != null && versnr !== '') return t('Versicherter')
  if (personId != null && personId !== '') return t('Partner')
  return t('Interessent')
}

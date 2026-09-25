import { i18nBuilder } from 'keycloakify/login'

/** Keycloak's own messages, for the built-in pages this theme leaves to Keycloakify. */
export const { useI18n, ofTypeI18n } = i18nBuilder.build()
export type I18n = typeof ofTypeI18n

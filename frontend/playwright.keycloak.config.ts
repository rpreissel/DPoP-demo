import { defineConfig } from '@playwright/test'

/**
 * The login pages as Keycloak really serves them, in both themes (FreeMarker and Keycloakify,
 * docs/ideen/keycloakify-statt-freemarker.md). Unlike playwright.config.ts this suite starts no
 * server: it needs the whole compose stack - Keycloak with both themes and the orchestrator that
 * switches between them - already running (`podman compose up -d`). Not part of CI for that reason.
 *
 * ORCHESTRATOR_URL / KEYCLOAK_URL point elsewhere; ADMIN_USER / ADMIN_PASSWORD are the demo's own
 * admin login (application.yml).
 */
export default defineConfig({
  testDir: './e2e-keycloak',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  use: {
    // German, so the wordings are the de bundle's (e2e-keycloak/texts.ts).
    locale: 'de-DE',
    // The compose Keycloak serves a self-signed certificate.
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
  },
})

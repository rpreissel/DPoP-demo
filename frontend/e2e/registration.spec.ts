import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'

/**
 * Full registration -> enrollment -> authenticated flow against the real backend: proves real
 * DPoP WebCrypto proofs are accepted end-to-end and that the security-summary screen's on-demand
 * backfill fetch (docs/05-api.md #2: currentAcr/currentAmr/activeMethods are never part of a tool
 * response) actually lands real data from the real `GET /channels/{id}`, not a mock.
 *
 * Uses the form's pre-filled KVNR (A123456789, one of only 3 seeded persons - V2__testdata.sql)
 * unchanged. This only works against a DB with no account provisioned yet for that KVNR: an
 * already-provisioned account resolves to LOGIN/AUTH instead of REGISTRATION/ENROLL. That is
 * guaranteed by playwright.config.ts, which always starts its own server on a fresh in-memory DB -
 * no manual dev-DB reset needed, and the dev instance on 8080 is never touched.
 */
test('register with ident-fsc, enroll SMS, and reach the authenticated security summary', async ({ page }) => {
  await completeRegistration(page)

  // The security summary's account fields only ever arrive via the on-demand GET backfill (never
  // inline in the tool response that settled `next` into authenticated) - if this renders, the
  // real backfill fetch against the real backend succeeded.
  await expect(page.locator('li:has-text("Sicherheitsniveau (ACR)")')).toContainText(/loa[12]/)
  await expect(page.locator('li:has-text("Nachgewiesene Methoden (AMR)")')).toContainText('sms')
  await expect(page.getByRole('button', { name: 'Deaktivieren' }).first()).toBeVisible()
})

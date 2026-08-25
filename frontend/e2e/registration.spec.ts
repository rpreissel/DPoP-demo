import { expect, test } from '@playwright/test'

/**
 * Full registration -> enrollment -> authenticated flow against the real backend: proves real
 * DPoP WebCrypto proofs are accepted end-to-end and that the security-summary screen's on-demand
 * backfill fetch (docs/05-api.md #2: currentAcr/currentAmr/activeMethods are never part of a tool
 * response) actually lands real data from the real `GET /app/channels/{id}`, not a mock.
 *
 * Uses the form's pre-filled KVNR (A123456789, one of only 3 seeded persons - V2__testdata.sql)
 * unchanged. This only works against a DB with no account provisioned yet for that KVNR: an
 * already-provisioned account (e.g. from a previous run against the same file-based H2 dev
 * database) resolves to LOGIN/AUTH instead of REGISTRATION/ENROLL. Reset the dev DB first if this
 * test needs to run twice: `rm data/dpopdb.mv.db data/dpopdb.trace.db` (repo root) before `bootRun`.
 */
test('register with ident-fsc, enroll SMS, and reach the authenticated security summary', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'Verbinden (automatisch)' }).click()

  await page.getByRole('button', { name: 'Identifizieren' }).click()

  // Two enroll candidates (sms, email) trigger a selection page instead of skipping straight to
  // one - pick SMS explicitly if offered.
  const smsChoice = page.getByRole('button', { name: /SMS/ })
  const codeSenden = page.getByRole('button', { name: 'Code senden' })
  await expect(smsChoice.or(codeSenden)).toBeVisible({ timeout: 10_000 })
  if (await smsChoice.isVisible()) await smsChoice.click()

  await page.getByRole('button', { name: 'Code senden' }).click()
  // Demo mode prefills the just-issued TAN (server never returns it outside `demo`, docs/05-api.md #2).
  await page.getByRole('button', { name: 'TAN bestätigen' }).click()

  await expect(page.getByRole('heading', { name: 'Authentifizierung erfolgreich!' })).toBeVisible({ timeout: 10_000 })

  // The security summary's account fields only ever arrive via the on-demand GET backfill (never
  // inline in the tool response that settled `next` into authenticated) - if this renders, the
  // real backfill fetch against the real backend succeeded.
  await expect(page.locator('li:has-text("Sicherheitsniveau (ACR)")')).toContainText(/loa[12]/)
  await expect(page.locator('li:has-text("Nachgewiesene Methoden (AMR)")')).toContainText('sms')
  await expect(page.getByRole('button', { name: 'Deaktivieren' })).toBeVisible()
})

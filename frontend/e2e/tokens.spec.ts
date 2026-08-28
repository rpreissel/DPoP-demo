import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'

/**
 * The mock Keycloak token surface on the authenticated screen, against the real backend: that the
 * AccessToken really is a parseable JWT carrying this session's own acr/amr, that calling the
 * single token endpoint again with a high minValiditySeconds actually mints a new one, and that
 * idclaims resolves real account data. Complements registration.spec.ts, which stops at the
 * security summary.
 */
test('mock AccessToken carries acr/amr, refreshes on demand, and idclaims resolves the account', async ({ page }) => {
  await completeRegistration(page)

  await expect(page.getByRole('heading', { name: 'Mock-Keycloak-AccessToken' })).toBeVisible({ timeout: 10_000 })
  const panel = page.locator('.card:has-text("Mock-Keycloak-AccessToken")')
  const claimRow = (claim: string) => panel.locator('li').filter({ has: page.getByText(claim, { exact: true }) })

  // acr/amr come out of the JWT payload the client parsed itself - proof the token is a real
  // three-segment JWT and that its claims mirror this session's evidence, not placeholders.
  await expect(claimRow('acr')).toContainText('loa2')
  await expect(claimRow('amr')).toContainText('fsc')
  await expect(claimRow('iss')).toContainText('mock-keycloak')

  // One endpoint covers issuance and refresh: the button asks for more remaining validity than any
  // live token can have, so the backend must mint a new one.
  const accessTokenValue = panel.locator('li:has-text("AccessToken") .value')
  const before = await accessTokenValue.innerText()
  await page.getByRole('button', { name: 'AccessToken aktualisieren' }).click()
  await expect(accessTokenValue).not.toHaveText(before, { timeout: 5_000 })

  // idclaims is a separate resource from the AccessToken's own claims. Asserted on the identity
  // fields every registration path sets - `email` is deliberately not among them, since whether
  // an e-mail address ends up on the account depends on which enrollments the journey chose.
  await page.getByRole('button', { name: 'ID-Claims laden' }).click()
  await expect(claimRow('accountId')).toBeVisible({ timeout: 5_000 })
  await expect(claimRow('personId')).toBeVisible()
  await expect(claimRow('auth_time')).toBeVisible()

  // The RefreshToken value must never reach the client - only its expiry is exposed.
  await expect(panel).toContainText('RefreshToken gültig noch')
  await expect(panel).not.toContainText('mockrt_')
})

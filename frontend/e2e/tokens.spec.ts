import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'
import { ui, uiPattern } from './texts'

/**
 * The token surface on the authenticated screen, against the real backend: that the AccessToken
 * really is a parseable JWT carrying this session's own acr/amr, that asking for more remaining
 * validity than any live token can have actually mints a new one, and that the ID-token claims
 * resolve to real account data. Complements registration.spec.ts, which stops at the security
 * summary.
 */
test('AccessToken carries acr/amr, refreshes on demand, and ID claims resolve the account', async ({ page }) => {
  await completeRegistration(page)

  const panel = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'AccessToken', exact: true }) })
  await expect(panel).toBeVisible({ timeout: 10_000 })

  // Token value and parsed claims live behind the disclosure - the screen leads with what a user
  // cares about (how long it is valid) and keeps the technical view one click away.
  await panel.getByText(ui('Technische Details (Token, Claims)')).click()

  // `has:` resolves relative to the matched <li>, so the inner locator must come from `page`,
  // not from `panel` - a panel-scoped one never matches anything inside the row.
  const claimRow = (claim: string) => panel.locator('li').filter({ has: page.getByText(claim, { exact: true }) })

  // acr/amr come out of the JWT payload the client parsed itself - proof the token is a real
  // three-segment JWT and that its claims mirror this session's evidence, not placeholders.
  await expect(claimRow('acr')).toContainText(/loa[12]/)
  await expect(claimRow('amr')).toContainText('fsc')
  await expect(claimRow('iss')).toContainText('mock-keycloak')

  // One endpoint covers issuance and refresh: the button asks for more remaining validity than any
  // live token can have, so the backend must mint a new one.
  const accessTokenValue = claimRow('AccessToken').locator('.value')
  const before = await accessTokenValue.innerText()
  await page.getByRole('button', { name: ui('AccessToken aktualisieren') }).click()
  await expect(accessTokenValue).not.toHaveText(before, { timeout: 5_000 })

  // The ID-token claims are a separate resource from the AccessToken's own claims, fetched on
  // reaching this screen. They resolved if the register binding shows up as an account status -
  // it is read straight off `claims.personId`, so it cannot render without them.
  await expect(page.getByText(uiPattern('Versicherter', 'Interessent'))).toBeVisible({ timeout: 10_000 })

  // The RefreshToken value must never reach the client - only its expiry is exposed.
  await expect(panel).toContainText(ui('RefreshToken gültig noch'))
  await expect(panel).not.toContainText('mockrt_')
})

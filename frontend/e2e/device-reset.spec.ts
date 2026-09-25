import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'
import { ui } from './texts'

/**
 * "Dieses Gerät zurücksetzen" on a linked device's start screen: like reinstalling the app, the
 * device key goes, so the next start screen is the one of a device no account knows - the account
 * itself stays and can still be reached by email.
 */
test('resetting a linked device leads to the start screen of an unknown device', async ({ page }) => {
  await completeRegistration(page)
  const phone = page.locator('.phone')

  // Once on the welcome, once more to answer the backend's own "really sign out?" prompt.
  await phone.getByRole('button', { name: ui('Abmelden'), exact: true }).click()
  await expect(phone.getByRole('heading', { name: /./ }).first()).toBeVisible()
  await phone.getByRole('button', { name: ui('Abmelden'), exact: true }).click()
  // Signing out lands straight on the start screen - no page in between.
  await expect(phone.getByRole('button', { name: ui('Anderes Konto benutzen') })).toBeVisible()

  await phone.getByRole('button', { name: ui('Dieses Gerät zurücksetzen') }).click()
  await phone.getByRole('button', { name: ui('Zurücksetzen'), exact: true }).click()

  await expect(phone.getByRole('button', { name: ui('Neues Konto anlegen') })).toBeVisible()
  await expect(phone.getByRole('button', { name: ui('Anderes Konto benutzen') })).toHaveCount(0)
})

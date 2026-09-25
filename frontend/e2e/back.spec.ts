import { expect, test } from '@playwright/test'
import { ui, uiPattern } from './texts'

/**
 * "Zurück" leaves a tool without declining it (docs/05-api.md, "Zurück und Verfahren wechseln"):
 * the selection it came from comes back, the tool still on it. Before, going back from the
 * Freischaltcode declined it, and with only one identification left the user landed straight in
 * the next one without any choice.
 */
test('going back from the Freischaltcode shows the identification choice again', async ({ page }) => {
  await page.goto('/app/')
  await page.getByRole('button', { name: ui('Automatisch anmelden') }).click()

  const fsc = page.getByRole('button', { name: uiPattern('Freischaltcode') })
  await fsc.click()
  await expect(page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') })).toBeVisible()

  await page.getByRole('button', { name: ui('Zurück'), exact: true }).click()

  // The choice again - both ways, the Freischaltcode included.
  await expect(fsc).toBeVisible()
  await expect(page.getByRole('button', { name: uiPattern('eID') })).toBeVisible()
})

import { expect, type Page } from '@playwright/test'
import { ui, uiPattern } from './texts'

/**
 * Drives a fresh REGISTRATION journey to the authenticated screen.
 *
 * Deliberately loop-driven rather than a fixed click sequence: how many steps registration takes
 * is a backend policy decision, not a UI constant. Identification alone doesn't satisfy the
 * required ACR, so the orchestrator chains enrollments (SMS, then e-mail) until it does - a
 * hard-coded sequence silently rots whenever that policy or the tool catalog changes, which is
 * exactly how this suite broke when ident-eid was added.
 */
export async function completeRegistration(page: Page): Promise<void> {
  // Straight to the App channel, not via `/`: the root is the channel-CHOICE page, and its
  // "Zum App-Kanal" link opens a named tab (target=...), which Playwright would follow into a
  // second page object. The suite is about the journey, not about that hop.
  await page.goto('/app/')
  await page.getByRole('button', { name: ui('Automatisch anmelden') }).click()

  // Two identification candidates (ident-eid, ident-fsc) mean a selection page rather than a skip
  // straight to the single one - pick Freischaltcode, whose form is fully pre-filled in demo mode.
  // Two screens (personal data, then the code) - both pre-filled.
  await page.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.getByRole('button', { name: ui('Identifizieren') }).click()

  // The welcome that greets a logged-in user - its name part varies, so only the words before it.
  const success = page.getByRole('heading', { name: new RegExp(`^${ui('Willkommen, {name}!').split('{name}')[0]}`) })

  for (let step = 0; step < 12 && !(await success.isVisible()); step++) {
    // Every click re-renders the step and detaches the button mid-action - settle first rather
    // than racing the re-render.
    await page.waitForTimeout(600)

    // SMS first so the resulting amr is predictable for assertions; the rest are the generic
    // "send a code / confirm a code" steps every enroll-* tool shares. Demo mode pre-fills the
    // phone number, e-mail and the just-issued TAN/code, so no typing is needed.
    // 'Einrichten' closes any enroll-* form whose fields demo mode already pre-filled (password
    // today). It comes last so the more specific labels win when both are on screen.
    for (const name of [uiPattern('SMS'), ui('Code senden'), ui('TAN bestätigen'), ui('Code bestätigen'), ui('Einrichten')]) {
      const button = page.getByRole('button', { name }).first()
      if (await button.isVisible()) {
        await button.click()
        await page.waitForTimeout(800)
        break
      }
    }
  }

  await expect(success).toBeVisible({ timeout: 10_000 })
}

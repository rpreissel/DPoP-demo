import { expect, type Page } from '@playwright/test'

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
  await page.goto('/')
  await page.getByRole('button', { name: 'Verbinden (automatisch)' }).click()

  // Two identification candidates (ident-eid, ident-fsc) mean a selection page rather than a skip
  // straight to the single one - pick Freischaltcode, whose form is fully pre-filled in demo mode.
  await page.getByRole('button', { name: /Freischaltcode/ }).click()
  await page.getByRole('button', { name: 'Identifizieren' }).click()

  const success = page.getByRole('heading', { name: 'Authentifizierung erfolgreich!' })

  for (let step = 0; step < 12 && !(await success.isVisible()); step++) {
    // Every click re-renders the step and detaches the button mid-action - settle first rather
    // than racing the re-render.
    await page.waitForTimeout(600)

    // SMS first so the resulting amr is predictable for assertions; the rest are the generic
    // "send a code / confirm a code" steps every enroll-* tool shares. Demo mode pre-fills the
    // phone number, e-mail and the just-issued TAN/code, so no typing is needed.
    for (const name of [/SMS/, 'Code senden', 'TAN bestätigen', 'Code bestätigen'] as const) {
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

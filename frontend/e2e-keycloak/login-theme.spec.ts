import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { kc } from './texts'

/**
 * Both login themes against the real compose stack (docs/ideen/keycloakify-statt-freemarker.md):
 * the orchestrator's switch sets the realm's theme, and either theme carries a whole sign-in
 * through - same pages, same field names, same outcome. The note in the dark band says which
 * theme drew a page.
 */

const ORCHESTRATOR = process.env.ORCHESTRATOR_URL ?? 'http://localhost:8080'
const KEYCLOAK = process.env.KEYCLOAK_URL ?? 'https://localhost:8543'
const ADMIN = { username: process.env.ADMIN_USER ?? 'admin', password: process.env.ADMIN_PASSWORD ?? 'admin' }
/** What KcDemoAccountSeeder gives every demo account. */
const DEMO_PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo1234!'

type Theme = 'FREEMARKER' | 'KEYCLOAKIFY'
const MADE_WITH: Record<Theme, string> = { FREEMARKER: 'FreeMarker', KEYCLOAKIFY: 'Keycloakify' }

async function switchTheme(request: APIRequestContext, theme: Theme) {
  const headers = { Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString('base64')}` }
  const put = await request.put(`${ORCHESTRATOR}/orchestrator/admin/login-theme`, { headers, data: { theme } })
  expect(put.status()).toBe(200)
  const get = await request.get(`${ORCHESTRATOR}/orchestrator/admin/login-theme`, { headers })
  expect(await get.json()).toEqual({ theme })
}

/**
 * The web login as the demo website starts it - the QR test client, whose loa1 shows the
 * orchestrator's method selection. PKCE only has to be well-formed: the suite stops at the code.
 */
function loginUrl(): string {
  const url = new URL(`${KEYCLOAK}/realms/Demo/protocol/openid-connect/auth`)
  url.searchParams.set('client_id', 'dpop-demo-web-qr-test')
  url.searchParams.set('redirect_uri', `${ORCHESTRATOR}/`)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('scope', 'openid')
  url.searchParams.set('acr_values', '1')
  url.searchParams.set('code_challenge_method', 'S256')
  url.searchParams.set('code_challenge', 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  return url.toString()
}

async function expectDrawnBy(page: Page, theme: Theme) {
  await expect(page.getByText(kc('Erstellt mit {technik}', { technik: MADE_WITH[theme] }))).toBeVisible()
  await expect(page.getByText(kc('Sie sind jetzt bei Keycloak, dem Anmeldedienst dieser Website.'))).toBeVisible()
}

/**
 * Opens the SMS page and leaves it again - nothing is sent before "Weiter", so this stays clear
 * of the orchestrator's send throttle (three per account and window), which a suite run twice in
 * a row would otherwise hit.
 */
async function visitSmsAndGoBack(page: Page, theme: Theme) {
  await page.getByRole('button', { name: 'SMS', exact: true }).click()
  await expectDrawnBy(page, theme)
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await page.getByRole('button', { name: kc('Zurück') }).click()
  await expect(page.getByRole('button', { name: kc('Abbrechen') })).toBeVisible()
}

/** The whole sign-in by e-mail address and password: the demo person is pre-filled, the password is the demo accounts' own. */
async function signInByPassword(page: Page, theme: Theme) {
  await page.getByRole('button', { name: kc('Passwort'), exact: true }).click()
  await expectDrawnBy(page, theme)
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await page.getByLabel(kc('Passwort'), { exact: true }).fill(DEMO_PASSWORD)
  await page.getByRole('button', { name: kc('Weiter') }).click()

  // Signed in: Keycloak hands the browser back to the website with an authorization code.
  await page.waitForURL((url) => url.href.startsWith(`${ORCHESTRATOR}/`) && url.searchParams.has('code'))
}

test.afterAll(async ({ request }) => {
  // Back to the preset - the demo starts with FreeMarker.
  await switchTheme(request, 'FREEMARKER')
})

for (const theme of ['FREEMARKER', 'KEYCLOAKIFY'] as const) {
  test(`${MADE_WITH[theme]}: the method selection, the SMS page and back, then a whole sign-in`, async ({ page, request }) => {
    await switchTheme(request, theme)
    await page.goto(loginUrl())
    await expectDrawnBy(page, theme)
    await expect(page.getByRole('button', { name: kc('Abbrechen') })).toBeVisible()
    await visitSmsAndGoBack(page, theme)
    await signInByPassword(page, theme)
  })
}

for (const theme of ['FREEMARKER', 'KEYCLOAKIFY'] as const) {
  test(`${MADE_WITH[theme]}: "Zurück" on the Freischaltcode page shows the personal details again`, async ({ page, request }) => {
    await switchTheme(request, theme)
    await page.goto(loginUrl())
    await page.getByRole('link', { name: kc('Registrieren') }).click()
    await page.getByRole('button', { name: kc('Freischaltcode'), exact: true }).click()

    const personal = page.getByText(kc('Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:'))
    const code = page.getByText(kc('Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben.'))
    const visibleButton = (name: string) => page.getByRole('button', { name, exact: true }).filter({ visible: true })

    await expect(personal).toBeVisible()
    await visibleButton(kc('Weiter zur Freischaltcode-Eingabe')).click()
    await expect(code).toBeVisible()

    // Within the tool: back to the personal details, and on from there to the code again.
    await visibleButton(kc('Zurück')).click()
    await expect(personal).toBeVisible()
    await visibleButton(kc('Weiter zur Freischaltcode-Eingabe')).click()
    await expect(code).toBeVisible()
  })
}

test('switching at runtime changes the very next page, without a restart', async ({ page, request }) => {
  await switchTheme(request, 'FREEMARKER')
  await page.goto(loginUrl())
  await expectDrawnBy(page, 'FREEMARKER')

  // Mid-login: the next page Keycloak renders comes from the other theme, and the sign-in goes on.
  await switchTheme(request, 'KEYCLOAKIFY')
  await signInByPassword(page, 'KEYCLOAKIFY')
})

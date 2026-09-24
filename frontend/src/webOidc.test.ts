import { afterEach, describe, expect, it, vi } from 'vitest'
import { createWebOidc, LoginNotCompletedError } from './webOidc'

const { completeLoginIfRedirected } = createWebOidc({
  baseUrl: 'https://kc.example',
  realm: 'Demo',
  browserClientId: 'dpop-demo-web',
  qrTestClientId: 'dpop-demo-web-qr-test',
})

describe('completeLoginIfRedirected', () => {
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('turns a cancelled Keycloak login into a cancelled LoginNotCompletedError and scrubs the URL', async () => {
    window.history.replaceState(null, '', '/web/?error=access_denied&state=x')

    const err = await completeLoginIfRedirected().catch((e: unknown) => e)

    expect(err).toBeInstanceOf(LoginNotCompletedError)
    expect((err as LoginNotCompletedError).cancelled).toBe(true)
    expect(window.location.search).toBe('')
  })

  it('reports any other error as a failure, not a cancellation', async () => {
    window.history.replaceState(null, '', '/web/?error=server_error&error_description=kaputt')

    const err = (await completeLoginIfRedirected().catch((e: unknown) => e)) as LoginNotCompletedError

    expect(err.cancelled).toBe(false)
    expect(err.message).toContain('kaputt')
  })

  it('does nothing without code or error', async () => {
    window.history.replaceState(null, '', '/web/')
    expect(await completeLoginIfRedirected()).toBeNull()
  })
})

describe('createWebOidc', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('talks to the Keycloak, realm and client the server named - nothing hard-wired', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ access_token: 'a', refresh_token: 'r', expires_in: 60 }), { status: 200 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const oidc = createWebOidc({
      baseUrl: 'https://keycloak.apps.example',
      realm: 'Andere',
      browserClientId: 'web-client',
      qrTestClientId: 'qr-client',
    })

    const tokens = await oidc.refreshTokens('r0')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('https://keycloak.apps.example/realms/Andere/protocol/openid-connect/token')
    expect((init.body as URLSearchParams).get('client_id')).toBe('web-client')
    expect(tokens.clientId).toBe('web-client')
  })
})

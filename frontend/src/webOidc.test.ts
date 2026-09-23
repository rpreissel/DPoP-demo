import { afterEach, describe, expect, it } from 'vitest'
import { completeLoginIfRedirected, LoginNotCompletedError } from './webOidc'

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

import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppChannelApp } from './AppChannelApp'
import type { ChannelResponse } from '../../types'

vi.mock('../../dpop.ts', () => ({
  getOrCreateDpopKeyPair: vi.fn().mockResolvedValue({ keyPair: {} as CryptoKeyPair, publicJwk: {} as JsonWebKey }),
  computeJwkThumbprint: vi.fn().mockResolvedValue('fake-thumbprint'),
  resetDpopKeyPair: vi.fn().mockResolvedValue(undefined),
}))

const api = vi.hoisted(() => ({
  createChannel: vi.fn(),
  getChannel: vi.fn(),
  raiseRequiredAcr: vi.fn(),
  cancelJourney: vi.fn(),
  logoutChannel: vi.fn(),
  getMethods: vi.fn(),
  startManageMethods: vi.fn(),
  deactivateMethod: vi.fn(),
  activateTool: vi.fn(),
  patchTool: vi.fn(),
  getTool: vi.fn(),
  startPeerLogin: vi.fn(),
}))

vi.mock('../../api.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api.ts')>()
  return { ...actual, ...api, onApiCall: () => () => {} }
})

/** Fills in only what applyResponse actually reads; individual tests override per case. */
function channelResponse(overrides: Partial<ChannelResponse> & { channel: ChannelResponse['channel'] }): ChannelResponse {
  return { next: undefined, stepData: undefined, demo: undefined, ...overrides }
}

beforeEach(() => {
  window.localStorage.clear()
  // AppChannelApp is its own page now (no more Willkommen section to navigate away from) - still
  // reset the hash/search so a later test doesn't inherit whichever sub-tab/query the previous one
  // left behind.
  window.location.hash = ''
  window.history.replaceState(null, '', '/')
  vi.clearAllMocks()
})

afterEach(() => {
  cleanup()
})

describe('resume mid-tool (docs/05-api.md #2: next.toolSessionId)', () => {
  it('reuses the running ToolSession instead of reactivating the tool', async () => {
    window.localStorage.setItem('dpop-demo-channel-session-id', 'chan-1')
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: { type: 'tool', toolId: 'enroll-sms', step: 'tanInput', toolSessionId: 'ts-resumed' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()

    const resumeButton = await screen.findByRole('button', { name: /Sitzung fortsetzen/ })
    await user.click(resumeButton)

    // The resumed step (tanInput) renders directly - no second activation round-trip, no second TAN.
    await screen.findByRole('heading', { name: 'TAN eingeben' })
    expect(api.activateTool).not.toHaveBeenCalled()
  })
})

describe('security-summary backfill (docs/05-api.md #2: on-demand, not part of tool responses)', () => {
  it('fetches currentAcr/currentAmr/activeMethods once, only after settling into authenticated', async () => {
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: { type: 'tool', toolId: 'enroll-sms', step: 'enroll', toolSessionId: 'ts-1' },
      })
    )
    // Matches the real backend contract: a tool response settling into authenticated still
    // carries no account fields - the client must fetch them explicitly.
    api.patchTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'AUTHENTICATED' },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: {
          channelSessionId: 'chan-1',
          channelType: 'APP',
          state: 'AUTHENTICATED',
          currentAcr: 'loa1',
          currentAmr: ['sms'],
          activeMethods: [{ id: 'method-1', method: 'sms' }],
        },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Automatisch anmelden' }))
    await user.click(await screen.findByRole('button', { name: 'Code senden' }))

    await screen.findByText('loa1')
    expect(api.getChannel).toHaveBeenCalledTimes(1)
    // activeMethods backfilled too: the deactivatable method row renders.
    expect(screen.getByRole('button', { name: 'Deaktivieren' })).toBeInTheDocument()

    // Re-renders after the backfill lands must not trigger a second fetch.
    await waitFor(() => expect(api.getChannel).toHaveBeenCalledTimes(1))
  })

  it('skips the backfill fetch when the channel-level response already carries the fields', async () => {
    // e.g. a step-up that turns out to already be satisfied - raiseRequiredAcr is a genuine
    // channel endpoint, so its own response already includes the account fields.
    window.localStorage.setItem('dpop-demo-channel-session-id', 'chan-1')
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: {
          channelSessionId: 'chan-1',
          channelType: 'APP',
          state: 'AUTHENTICATED',
          currentAcr: 'loa2',
          currentAmr: ['sms', 'password'],
          activeMethods: [
            { id: 'method-1', method: 'sms' },
            { id: 'method-2', method: 'password' },
          ],
        },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Sitzung fortsetzen/ }))

    await screen.findByText('loa2')
    expect(api.getChannel).toHaveBeenCalledTimes(1) // the resume GET itself - no extra backfill call
  })
})

describe('URL-Einstieg per intent (docs/10-frontend.md #1)', () => {
  it('startet confirm_peer_login automatisch und bereinigt die URL', async () => {
    window.history.replaceState(null, '', '/?intent=confirm_peer_login&pairingCode=AB3D-7KQ2')
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS' },
        next: { type: 'tool', toolId: 'auth-device', step: 'auth', toolSessionId: 'ts-1' },
      })
    )

    render(<AppChannelApp />)

    await waitFor(() => expect(api.createChannel).toHaveBeenCalledWith(expect.anything(), undefined, 'confirm_peer_login', expect.anything()))
    expect(window.location.search).toBe('')
  })

  it('bestätigt über den bereits authentifizierten Channel statt einen neuen anzulegen', async () => {
    window.localStorage.setItem('dpop-demo-channel-session-id', 'chan-1')
    window.history.replaceState(null, '', '/?intent=confirm_peer_login&pairingCode=AB3D-7KQ2')
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'AUTHENTICATED', currentAcr: 'loa2' },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )
    api.startPeerLogin.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS' },
        next: { type: 'tool', toolId: 'confirm-qr-login', step: 'input', toolSessionId: 'ts-2' },
      })
    )

    render(<AppChannelApp />)

    await waitFor(() => expect(api.startPeerLogin).toHaveBeenCalledWith(expect.anything(), 'chan-1'))
    expect(api.createChannel).not.toHaveBeenCalled()
  })
})

describe('Back-Button bis zur Startauswahl (docs/10-frontend.md #1)', () => {
  it('verlässt einen laufenden Vorgang lokal, ohne Backend-Aufruf', async () => {
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: { type: 'tool', toolId: 'ident-fsc', step: 'input', toolSessionId: 'ts-1' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Neues Konto registrieren' }))
    await screen.findByRole('heading', { name: /Freischaltcode/ })

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))

    await screen.findByRole('heading', { name: 'Wie möchten Sie starten?' })
    expect(api.cancelJourney).not.toHaveBeenCalled()
  })
})

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DpopKeyPair } from '../../dpop'
import { loadUnlockSecret, storeUnlockSecret } from '../../kobilUnlockSecret'
import type { ToolRenderContext } from '../types'
import kobilModules, { authKobilTool, enrollKobilTool } from './index'

vi.mock('../../kobilSdk', () => ({
  activate: vi.fn(() => Promise.resolve({ deviceId: 'dev-1' })),
  login: vi.fn(() => Promise.resolve({ otp: '11112222' })),
}))
vi.mock('./api', () => ({
  submitKobilStep: vi.fn(),
  releaseKobilPin: vi.fn(),
}))

import { activate, login } from '../../kobilSdk'
import { releaseKobilPin, submitKobilStep } from './api'

function ctx(overrides: Partial<ToolRenderContext>): ToolRenderContext {
  return {
    step: '',
    toolId: '',
    toolSessionId: 'tool-session-1',
    proof: { kind: 'dpop', dpop: {} as DpopKeyPair },
    onResult: vi.fn(),
    onError: vi.fn(),
    ...overrides,
  }
}

afterEach(() => {
  // Explicit: this project's vitest setup registers only jest-dom, not RTL's auto-cleanup, so two
  // renders in one file would otherwise share a DOM.
  cleanup()
  vi.clearAllMocks()
  localStorage.clear()
})

describe('the kobil tool modules', () => {
  it('registers exactly the enroll/auth pair', () => {
    expect(kobilModules.map((module) => module.toolId)).toEqual(['enroll-kobil', 'auth-kobil'])
  })

  it('renders nothing for a step that is not its own', () => {
    expect(enrollKobilTool.render(ctx({ step: 'auth', toolId: 'enroll-kobil' }))).toBeNull()
    expect(authKobilTool.render(ctx({ step: 'enroll', toolId: 'auth-kobil' }))).toBeNull()
  })
})

describe('enroll-kobil/activate', () => {
  const stepData = {
    kind: 'kobil-activation',
    missingFields: ['activated', 'biometricConsent'],
    tenantId: 'dpop-demo',
    kobilUserId: 'kob-1',
    activationCode: 'ABC123',
    pin: '40318827',
    unlockSecret: 'the-secret',
  }

  it('runs the SDK activation, keeps the unlock secret locally and confirms to the backend', async () => {
    render(<>{enrollKobilTool.render(ctx({ step: 'activate', toolId: 'enroll-kobil', stepData }))}</>)

    fireEvent.click(screen.getByRole('button', { name: 'Weiter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Biometrie erlauben' }))

    await waitFor(() => expect(activate).toHaveBeenCalled())
    expect(activate).toHaveBeenCalledWith({ tenantId: 'dpop-demo', userId: 'kob-1' }, 'ABC123', '40318827')
    // The secret stays here; only its hash lives on the server.
    expect(loadUnlockSecret('kob-1')).toBe('the-secret')
    await waitFor(() =>
      expect(submitKobilStep).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ activated: true, biometricConsent: true })
      )
    )
  })

  it('declining biometrics leaves no secret behind - the consent has a consequence', async () => {
    render(<>{enrollKobilTool.render(ctx({ step: 'activate', toolId: 'enroll-kobil', stepData }))}</>)

    fireEvent.click(screen.getByRole('button', { name: 'Weiter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Nur mit Passwort' }))

    await waitFor(() => expect(activate).toHaveBeenCalled())
    expect(loadUnlockSecret('kob-1')).toBeNull()
    await waitFor(() =>
      expect(submitKobilStep).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ activated: true, biometricConsent: false })
      )
    )
  })

  it('never shows the PIN - the user neither chose it nor needs to know it', () => {
    render(<>{enrollKobilTool.render(ctx({ step: 'activate', toolId: 'enroll-kobil', stepData }))}</>)
    fireEvent.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.queryByText(/40318827/)).toBeNull()
  })
})

describe('auth-kobil/unlock', () => {
  const stepData = { kind: 'kobil-unlock', tenantId: 'dpop-demo', kobilUserId: 'kob-1', unlockOptions: ['biometric', 'password'] }
  const passwordOnly = { kind: 'kobil-unlock', tenantId: 'dpop-demo', kobilUserId: 'kob-1', unlockOptions: ['password'] }

  it('releases with the locally stored secret when one is present', () => {
    storeUnlockSecret('kob-1', 'the-secret')
    render(<>{authKobilTool.render(ctx({ step: 'unlock', toolId: 'auth-kobil', stepData }))}</>)

    fireEvent.click(screen.getByRole('button', { name: 'Mit Biometrie entsperren' }))
    expect(releaseKobilPin).toHaveBeenCalledWith(expect.anything(), { kind: 'biometric', unlockSecret: 'the-secret' })
  })

  it('offers the password instead when this browser holds no secret', () => {
    render(<>{authKobilTool.render(ctx({ step: 'unlock', toolId: 'auth-kobil', stepData }))}</>)

    // The control is absent, not disabled - nothing dead to click (KobilUnlockGate).
    expect(screen.queryByRole('button', { name: 'Mit Biometrie entsperren' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Mit Passwort entsperren' }))
    fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: 'hunter2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Entsperren' }))

    expect(releaseKobilPin).toHaveBeenCalledWith(expect.anything(), { kind: 'password', password: 'hunter2' })
  })

  it('never offers a means the backend did not name, even with a secret in this browser', () => {
    storeUnlockSecret('kob-1', 'the-secret')
    render(<>{authKobilTool.render(ctx({ step: 'unlock', toolId: 'auth-kobil', stepData: passwordOnly }))}</>)

    // Consent was declined at setup, so there is no server-side counterpart - a local leftover
    // must not resurrect the biometric path.
    expect(screen.queryByRole('button', { name: 'Mit Biometrie entsperren' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Mit Passwort entsperren' })).toBeInTheDocument()
  })

  it('says so plainly when no means is left at all', () => {
    const noOptions = { kind: 'kobil-unlock', tenantId: 'dpop-demo', kobilUserId: 'kob-1', unlockOptions: [] }
    render(<>{authKobilTool.render(ctx({ step: 'unlock', toolId: 'auth-kobil', stepData: noOptions }))}</>)

    expect(screen.queryByRole('button', { name: 'Mit Biometrie entsperren' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Mit Passwort entsperren' })).toBeNull()
    expect(screen.getByText(/keinen Entsperrweg/)).toBeInTheDocument()
  })
})

describe('auth-kobil/otp', () => {
  it('runs the SDK login with the released PIN on its own and submits only the OTP', async () => {
    const stepData = { kind: 'kobil-otp', missingFields: ['otp'], tenantId: 'dpop-demo', kobilUserId: 'kob-1', kobilPin: '40318827' }
    render(<>{authKobilTool.render(ctx({ step: 'otp', toolId: 'auth-kobil', stepData }))}</>)

    await waitFor(() => expect(login).toHaveBeenCalledWith({ tenantId: 'dpop-demo', userId: 'kob-1' }, '40318827'))
    await waitFor(() => expect(submitKobilStep).toHaveBeenCalledWith(expect.anything(), { otp: '11112222' }))
  })

  it('falls back to the unlock screen when the step is reached without a PIN - a reload after the release', () => {
    const stepData = { kind: 'kobil-otp', missingFields: ['otp'], tenantId: 'dpop-demo', kobilUserId: 'kob-1' }
    render(<>{authKobilTool.render(ctx({ step: 'otp', toolId: 'auth-kobil', stepData }))}</>)

    // No PIN in hand means there is nothing to run the SDK with; the only way on is another unlock.
    expect(login).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Anmelden mit KOBIL' })).toBeInTheDocument()
  })
})

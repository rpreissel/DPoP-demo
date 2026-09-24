import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./dpop.ts', () => ({
  createDpopProof: vi.fn().mockResolvedValue('fake-proof'),
}))

import { ApiError, describeError, getChannel } from './api.ts'
import type { DpopKeyPair } from './dpop.ts'

const dpop = {} as DpopKeyPair

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status })
}

describe('call()s retry on CONCURRENT_MODIFICATION (docs/07-betrieb.md #1: "the loser should retry")', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('retries once and returns the second attempt\'s result on a single conflict', async () => {
    const channel = { channelSessionId: 'chan-1', channelType: 'APP', state: 'AUTHENTICATED' }
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(409, { error: 'CONCURRENT_MODIFICATION', message: 'retry' }))
      .mockResolvedValueOnce(jsonResponse(200, { channel }))

    const result = await getChannel(dpop, 'chan-1')

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(result.channel).toEqual(channel)
  })

  it('never retries a second time - a repeated conflict surfaces as an ApiError', async () => {
    vi.mocked(fetch).mockImplementation(async () => jsonResponse(409, { error: 'CONCURRENT_MODIFICATION', message: 'retry' }))

    await expect(getChannel(dpop, 'chan-1')).rejects.toMatchObject({ status: 409, errorCode: 'CONCURRENT_MODIFICATION' })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('does not retry a different error code', async () => {
    vi.mocked(fetch).mockImplementation(async () => jsonResponse(404, { error: 'NOT_FOUND', message: 'gone' }))

    await expect(getChannel(dpop, 'chan-1')).rejects.toBeInstanceOf(ApiError)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})

describe('describeError', () => {
  it('points an expired process (PROCESS_GONE) to "Vergessen" as the way to start over', () => {
    const text = describeError('Fehler', new ApiError(410, 'PROCESS_GONE', 'Process is gone'))
    expect(text).toContain('Vergessen')
  })

  it('shows only the server\'s own message for PROCESS_ABORTED, which shares the 410 status', () => {
    // A QR link opened on a device with no account: the server says what to do - "Vergessen"
    // would not help and used to be appended to every 410.
    const text = describeError(
      'Web-Login-Bestätigung fehlgeschlagen',
      new ApiError(410, 'PROCESS_ABORTED', 'Dieses Gerät ist noch keinem Konto zugeordnet - bitte zuerst regulär anmelden.'),
    )
    expect(text).toBe('Web-Login-Bestätigung fehlgeschlagen: Dieses Gerät ist noch keinem Konto zugeordnet - bitte zuerst regulär anmelden.')
  })
})

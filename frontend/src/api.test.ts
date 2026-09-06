import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./dpop.ts', () => ({
  createDpopProof: vi.fn().mockResolvedValue('fake-proof'),
}))

import { ApiError, getChannel } from './api.ts'
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

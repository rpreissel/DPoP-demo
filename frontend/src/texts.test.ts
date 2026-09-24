import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { APP_TEXTS, loadTexts, resetTexts, resolveText } from './texts'

function respond(status: number, body?: unknown, etag?: string) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: etag ? { ETag: etag } : {},
  })
}

describe('texts', () => {
  beforeEach(() => {
    resetTexts()
    localStorage.clear()
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('en-GB')
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['en-GB'])
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads the bundle in the browser language and resolves placeholders, nested texts first-class', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      respond(200, { a: 'Limit reached: {reason} ({tries})', b: 'invalid TAN', c: 'knowledge', d: 'possession', e: 'Factors: {f}' }, '"v1"'),
    )
    await loadTexts(APP_TEXTS)
    expect(fetchMock).toHaveBeenCalledWith(`${APP_TEXTS}/en`, { headers: {} })
    expect(resolveText({ key: 'a', args: { tries: '3' }, texts: { reason: [{ key: 'b' }] } })).toBe('Limit reached: invalid TAN (3)')
    expect(resolveText({ key: 'e', texts: { f: [{ key: 'c' }, { key: 'd' }] } })).toBe('Factors: knowledge, possession')
  })

  it('revalidates with the stored ETag and keeps the cached copy on 304', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(respond(200, { a: 'cached' }, '"v1"'))
    await loadTexts(APP_TEXTS)
    resetTexts()

    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(respond(304, undefined, '"v1"'))
    await loadTexts(APP_TEXTS)
    expect(fetchMock).toHaveBeenCalledWith(`${APP_TEXTS}/en`, { headers: { 'If-None-Match': '"v1"' } })
    expect(resolveText({ key: 'a' })).toBe('cached')
  })

  it('still works when localStorage throws', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(respond(200, { a: 'fresh' }, '"v2"'))
    await loadTexts(APP_TEXTS)
    expect(resolveText({ key: 'a' })).toBe('fresh')
  })

  it('shows an unknown id as itself, never as nothing', () => {
    expect(resolveText({ key: '3f9a1c0b2e7d' })).toBe('3f9a1c0b2e7d')
  })
})

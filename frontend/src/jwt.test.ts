import { describe, expect, it } from 'vitest'
import { parseJwtPayload } from './jwt'

function base64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

describe('parseJwtPayload', () => {
  it('decodes the payload as UTF-8, so umlauts survive', () => {
    const token = `${base64Url('{"alg":"none"}')}.${base64Url('{"name":"Jürgen Müller","strasse":"Musterstraße 1"}')}.`

    expect(parseJwtPayload(token)).toEqual({ name: 'Jürgen Müller', strasse: 'Musterstraße 1' })
  })

  it('answers null for something that is not a JWT', () => {
    expect(parseJwtPayload('kein-token')).toBeNull()
  })
})

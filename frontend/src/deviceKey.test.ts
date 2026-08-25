import { describe, expect, it } from 'vitest'
import { computeJwkThumbprint } from './dpop.ts'
import { createDeviceProof } from './deviceKey.ts'

/**
 * IndexedDB-backed persistence (getOrCreateDeviceKeyPair) isn't covered here - this project's
 * jsdom test environment has no IndexedDB polyfill yet (App.test.tsx mocks dpop.ts wholesale
 * instead of exercising it directly, for the same reason). What IS testable without one, and
 * worth a real regression test, is the proof JWT this module builds - real WebCrypto is
 * available under Node/jsdom, unlike IndexedDB.
 */
describe('createDeviceProof', () => {
  async function generateKeyPair(): Promise<CryptoKeyPair> {
    return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  }

  function decodeSegment(segment: string): Record<string, unknown> {
    const padded = segment.replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(padded))
  }

  it('produces a three-segment JWT with typ=device-proof+jwt and the embedded public jwk', async () => {
    const keyPair = await generateKeyPair()
    const proof = await createDeviceProof(keyPair, 'PATCH', 'https://example.test/tools/abc/auth-device', 'pin')

    const segments = proof.split('.')
    expect(segments).toHaveLength(3)

    const header = decodeSegment(segments[0])
    expect(header.typ).toBe('device-proof+jwt')
    expect(header.alg).toBe('ES256')
    expect(header.jwk).toMatchObject({ kty: 'EC', crv: 'P-256' })
  })

  it('carries htm/htu/accessMeans and a fresh jti per call', async () => {
    const keyPair = await generateKeyPair()
    const htu = 'https://example.test/tools/abc/enroll-device'
    const proofA = await createDeviceProof(keyPair, 'PATCH', htu, 'biometric')
    const proofB = await createDeviceProof(keyPair, 'PATCH', htu, 'biometric')

    const payloadA = decodeSegment(proofA.split('.')[1])
    const payloadB = decodeSegment(proofB.split('.')[1])

    expect(payloadA).toMatchObject({ htm: 'PATCH', htu, accessMeans: 'biometric' })
    expect(payloadA.jti).not.toBe(payloadB.jti)
  })

  it('embeds a jwk whose thumbprint matches computeJwkThumbprint (same canonicalization as the backend)', async () => {
    const keyPair = await generateKeyPair()
    const proof = await createDeviceProof(keyPair, 'PATCH', 'https://example.test/x', 'pin')
    const header = decodeSegment(proof.split('.')[0])

    const publicJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)
    const expectedThumbprint = await computeJwkThumbprint({
      kty: publicJwk.kty,
      crv: publicJwk.crv,
      x: publicJwk.x,
      y: publicJwk.y,
    })
    const actualThumbprint = await computeJwkThumbprint(header.jwk as JsonWebKey)

    expect(actualThumbprint).toBe(expectedThumbprint)
  })
})

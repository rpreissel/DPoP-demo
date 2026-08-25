import { base64UrlEncode, exportPublicJwk } from './dpop.ts'

const DB_NAME = 'device-key-demo'
const STORE_NAME = 'device-keys'
const KEY_ID = 'default-device-key'

export interface DeviceKeyPair {
  keyPair: CryptoKeyPair
  publicJwk: JsonWebKey
}

// Deliberately a completely separate IndexedDB database from dpop.ts's ('dpop-demo') - this key
// represents the DEVICE's long-lived, account-bound credential (enroll-device/auth-device), never
// the per-channel DPoP key. resetDpopKeyPair() must never be able to touch it.
function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME)
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

async function storeKeyPair(keyPair: CryptoKeyPair): Promise<void> {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite')
    tx.objectStore(STORE_NAME).put(keyPair, KEY_ID)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

async function loadKeyPair(): Promise<CryptoKeyPair | undefined> {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly')
    const request = tx.objectStore(STORE_NAME).get(KEY_ID)
    request.onsuccess = () => resolve(request.result as CryptoKeyPair | undefined)
    request.onerror = () => reject(request.error)
  })
}

async function generateDeviceKeyPair(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify'])
}

/**
 * Load-or-generate, same as dpop.ts's getOrCreateDpopKeyPair - a device that already enrolled
 * with this key must keep getting the SAME key back, or auth-device would never recognize it
 * again (the whole point of "device binding").
 */
export async function getOrCreateDeviceKeyPair(): Promise<DeviceKeyPair> {
  let keyPair = await loadKeyPair()
  if (!keyPair) {
    keyPair = await generateDeviceKeyPair()
    await storeKeyPair(keyPair)
  }
  const publicJwk = await exportPublicJwk(keyPair)
  return { keyPair, publicJwk }
}

async function sign(payload: string, privateKey: CryptoKey): Promise<ArrayBuffer> {
  const encoder = new TextEncoder()
  return crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, encoder.encode(payload))
}

/**
 * Self-signed device-proof JWT (typ=device-proof+jwt), verified server-side by
 * DeviceProofValidator - structurally identical to a DPoP proof (own jwk in header, ES256,
 * htm/htu/iat/jti) but for the account-bound device credential, plus the accessMeans claim the
 * (demo-mocked) PIN/biometric confirmation determined for this one attempt.
 */
export async function createDeviceProof(
  keyPair: CryptoKeyPair,
  htm: string,
  htu: string,
  accessMeans: 'pin' | 'biometric',
): Promise<string> {
  const publicJwk = await exportPublicJwk(keyPair)
  const header = { typ: 'device-proof+jwt', alg: 'ES256', jwk: publicJwk }

  const nowSeconds = Math.floor(Date.now() / 1000)
  const payload = { jti: crypto.randomUUID(), iat: nowSeconds, htm, htu, accessMeans }

  const encodedHeader = base64UrlEncode(new TextEncoder().encode(JSON.stringify(header)))
  const encodedPayload = base64UrlEncode(new TextEncoder().encode(JSON.stringify(payload)))
  const signingInput = `${encodedHeader}.${encodedPayload}`
  const signature = await sign(signingInput, keyPair.privateKey)
  const encodedSignature = base64UrlEncode(signature)

  return `${signingInput}.${encodedSignature}`
}

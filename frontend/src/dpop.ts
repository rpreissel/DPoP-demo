const DB_NAME = 'dpop-demo'
const STORE_NAME = 'dpop-keys'
const KEY_ID = 'default-dpop-key'

export interface DpopKeyPair {
  keyPair: CryptoKeyPair
  publicJwk: JsonWebKey
}

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

async function deleteKeyPair(): Promise<void> {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite')
    tx.objectStore(STORE_NAME).delete(KEY_ID)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

export async function generateDpopKeyPair(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(
    {
      name: 'ECDSA',
      namedCurve: 'P-256',
    },
    false,
    ['sign', 'verify'],
  )
}

export async function exportPublicJwk(keyPair: CryptoKeyPair): Promise<JsonWebKey> {
  const jwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)
  return {
    kty: jwk.kty,
    crv: jwk.crv,
    x: jwk.x,
    y: jwk.y,
  }
}

export async function getOrCreateDpopKeyPair(): Promise<DpopKeyPair> {
  let keyPair = await loadKeyPair()
  if (!keyPair) {
    keyPair = await generateDpopKeyPair()
    await storeKeyPair(keyPair)
  }
  const publicJwk = await exportPublicJwk(keyPair)
  return { keyPair, publicJwk }
}

export async function resetDpopKeyPair(): Promise<void> {
  await deleteKeyPair()
}

/**
 * RFC 7638 JWK thumbprint - matches the backend's JwkThumbprintService member order exactly
 * (kty, crv, x, y; not strict lexicographic order) so this displays the SAME value the backend
 * derives as `bindingKeyRef`, not just "a" thumbprint.
 */
export async function computeJwkThumbprint(jwk: JsonWebKey): Promise<string> {
  const canonical = `{"kty":"${jwk.kty}","crv":"${jwk.crv}","x":"${jwk.x}","y":"${jwk.y}"}`
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical))
  return base64UrlEncode(hash)
}

export function base64UrlEncode(buffer: ArrayBuffer | Uint8Array): string {
  const bytes = buffer instanceof ArrayBuffer ? new Uint8Array(buffer) : buffer
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function sign(payload: string, privateKey: CryptoKey): Promise<ArrayBuffer> {
  const encoder = new TextEncoder()
  return crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    privateKey,
    encoder.encode(payload),
  )
}

export async function createDpopProof(
  keyPair: CryptoKeyPair,
  htm: string,
  htu: string,
  nonce?: string,
): Promise<string> {
  const publicJwk = await exportPublicJwk(keyPair)
  const header = {
    typ: 'dpop+jwt',
    alg: 'ES256',
    jwk: publicJwk,
  }

  const nowSeconds = Math.floor(Date.now() / 1000)
  const payload = {
    jti: crypto.randomUUID(),
    iat: nowSeconds,
    htm,
    htu,
    ...(nonce ? { nonce } : {}),
  }

  const encodedHeader = base64UrlEncode(new TextEncoder().encode(JSON.stringify(header)))
  const encodedPayload = base64UrlEncode(new TextEncoder().encode(JSON.stringify(payload)))
  const signingInput = `${encodedHeader}.${encodedPayload}`
  const signature = await sign(signingInput, keyPair.privateKey)
  const encodedSignature = base64UrlEncode(signature)

  return `${signingInput}.${encodedSignature}`
}

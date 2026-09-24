/**
 * Backend texts, resolved here (docs/adr/ADR-033). The backend never sends wording, only text
 * references (`{ key, args, texts }`); every language - German included - is a bundle the client
 * fetches from `GET <base>/{lang}` at start.
 *
 * The bundle is kept with its ETag in localStorage and revalidated on every start with
 * `If-None-Match`: a 304 means the copy is current, so "is there anything new?" and the download
 * are one request. localStorage is only a cache - when it is unavailable the bundle is simply
 * fetched in full.
 */

/** A text reference as it arrives in any response. */
export interface TextRef {
  key: string
  args?: Record<string, string>
  texts?: Record<string, TextRef[]>
}

/** This application's texts. */
export const APP_TEXTS = '/orchestrator/api/v1/texts'
/** The simulated foreign systems bring their own. */
export const NECT_TEXTS = '/mock-nect/texts'
export const KOBIL_TEXTS = '/mock-kobil/texts'
export const PERSONENVERZEICHNIS_TEXTS = '/mock-personenverzeichnis/texts'

const SUPPORTED = ['de', 'en'] as const
type Language = (typeof SUPPORTED)[number]

/** The browser's language if a bundle is written in it, otherwise German. */
export function language(): Language {
  const preferred = (typeof navigator !== 'undefined' ? navigator.languages?.[0] ?? navigator.language : undefined) ?? 'de'
  const base = preferred.toLowerCase().split('-')[0]
  return (SUPPORTED as readonly string[]).includes(base) ? (base as Language) : 'de'
}

const bundles = new Map<string, Record<string, string>>()

interface Cached {
  etag: string
  texts: Record<string, string>
}

function cacheKey(base: string, lang: string) {
  return `dpop-demo-texts:${base}:${lang}`
}

function readCache(key: string): Cached | undefined {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as Cached) : undefined
  } catch {
    return undefined
  }
}

function writeCache(key: string, value: Cached) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Only a cache: the next start fetches in full.
  }
}

/** Fetches (or revalidates) the bundle at [base] in the current language. Never throws: without texts, references show their key. */
export async function loadTexts(base: string): Promise<void> {
  const lang = language()
  const key = cacheKey(base, lang)
  const cached = readCache(key)
  try {
    const response = await fetch(`${base}/${lang}`, { headers: cached ? { 'If-None-Match': cached.etag } : {} })
    if (response.status === 304 && cached) {
      bundles.set(base, cached.texts)
      return
    }
    if (!response.ok) throw new Error(`${response.status}`)
    const texts = (await response.json()) as Record<string, string>
    bundles.set(base, texts)
    const etag = response.headers.get('ETag')
    if (etag) writeCache(key, { etag, texts })
  } catch {
    if (cached) bundles.set(base, cached.texts)
  }
}

/** All bundles a page needs, in parallel. */
export function loadAllTexts(...bases: string[]): Promise<void> {
  return Promise.all(bases.map(loadTexts)).then(() => undefined)
}

/**
 * A reference in the reader's language. `{name}` comes from `args` as it is, or from `texts`
 * resolved the same way (several joined with ", "). An id the bundle does not know shows as
 * itself rather than as nothing.
 */
export function resolveText(ref: TextRef | null | undefined, base: string = APP_TEXTS): string {
  if (!ref) return ''
  const wording = bundles.get(base)?.[ref.key] ?? ref.key
  return fill(wording, (name) => {
    const nested = ref.texts?.[name]
    if (nested) return nested.map((n) => resolveText(n, base)).join(', ')
    return ref.args?.[name]
  })
}

// ------------------------------------------------------------------ the frontend's own texts

/**
 * The frontend's own user-facing text: `t("Weiter zu Nect")`, `t("{anzahl} Verfahren", { anzahl })`.
 * The German source wording stays in the code; its id (the same hash as the backend's
 * `Text.idOf`) is looked up in every loaded bundle, and the wording found there - in the reader's
 * language, German included - is what shows. Without a wording (no bundle loaded, e.g. in a unit
 * test) the template itself shows: unlike a backend reference, it is known here.
 *
 * The template must be a string literal: `scripts/text-catalog.mjs` collects them from the parsed
 * source for `/translate-texts` and refuses anything else (docs/adr/ADR-033).
 */
export function t(template: string, values?: Record<string, string | number>): string {
  return fill(wordingOf(template), (name) => (values && name in values ? String(values[name]) : undefined))
}

/** The wording shown for [template] - from the bundles, else the template itself. */
export function wordingOf(template: string): string {
  const id = textId(template)
  for (const texts of bundles.values()) {
    const wording = texts[id]
    if (wording !== undefined) return wording
  }
  return template
}

function fill(wording: string, value: (name: string) => string | undefined): string {
  return wording.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (placeholder, name: string) => value(name) ?? placeholder)
}

const ids = new Map<string, string>()

/** The first 12 hex digits of the template's SHA-256 - the backend's `Text.idOf`, computed synchronously. */
export function textId(template: string): string {
  let id = ids.get(template)
  if (id === undefined) {
    id = sha256Hex(new TextEncoder().encode(template)).slice(0, 12)
    ids.set(template, id)
  }
  return id
}

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
  0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
  0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
  0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

/** Plain SHA-256 (FIPS 180-4). Synchronous, unlike crypto.subtle - a render cannot wait for a promise. */
function sha256Hex(bytes: Uint8Array): string {
  const bitLength = bytes.length * 8
  const padded = new Uint8Array((((bytes.length + 9 + 63) >> 6) << 6))
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, Math.floor(bitLength / 0x100000000))
  view.setUint32(padded.length - 4, bitLength >>> 0)
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19])
  const w = new Uint32Array(64)
  const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n))
  for (let chunk = 0; chunk < padded.length; chunk += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(chunk + i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }
    let [a, b, c, d, e, f, g, hh] = h
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0
      hh = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }
    h[0] += a
    h[1] += b
    h[2] += c
    h[3] += d
    h[4] += e
    h[5] += f
    h[6] += g
    h[7] += hh
  }
  return Array.from(h, (x) => x.toString(16).padStart(8, '0')).join('')
}

/** For tests: forget loaded bundles. */
export function resetTexts() {
  bundles.clear()
}

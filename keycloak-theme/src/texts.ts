/**
 * The theme's own texts, the same way the FreeMarker theme does it (docs/adr/ADR-033): the German
 * source wording stays in the code, `t("Weiter")`; its id - a slug of its first words plus the first 6 hex digits of its SHA-256,
 * Java's `KcText.idOf` - is looked up in the wordings Keycloak renders into every orchestrator page
 * (`kcContext.texts`, set by the extension's WebFormRenderer from the login theme's messages, in
 * the login's language). So a reworded messages file shows without rebuilding this theme. A page
 * without them (Keycloak's own pages) shows the template itself.
 *
 * The template must be a string literal, so `/translate-texts` can collect it.
 */

let wordings: Record<string, string> = {}

/** The wordings of the page being rendered - `kcContext.texts`. */
export function setTexts(texts: Record<string, string> | undefined) {
  wordings = texts ?? {}
}

export function t(template: string, values?: Record<string, string | number>): string {
  const wording = wordings[textId(template)] ?? template
  return wording.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (placeholder, name: string) =>
    values && name in values ? String(values[name]) : placeholder,
  )
}

/** key=value lines, `#` comments - the shape /translate-texts writes (UTF-8, no escapes). */
export function parseProperties(source: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of source.split(/\r?\n/)) {
    if (line.startsWith('#') || !line.includes('=')) continue
    const at = line.indexOf('=')
    out[line.slice(0, at).trim()] = line.slice(at + 1)
  }
  return out
}

const ids = new Map<string, string>()

/** Java's `KcText.idOf`, computed synchronously - a render cannot wait for crypto.subtle. */
export function textId(template: string): string {
  let id = ids.get(template)
  if (id === undefined) {
    const hash = sha256Hex(new TextEncoder().encode(template)).slice(0, 6)
    const slug = textSlug(template)
    id = slug === '' ? hash : `${slug}-${hash}`
    ids.set(template, id)
  }
  return id
}

const SLUG_MAX = 40

/** `Text.slugOf` / `KcText.slugOf`, character for character. */
function textSlug(template: string): string {
  const words = template
    .toLowerCase()
    .replaceAll('ä', 'ae').replaceAll('ö', 'oe').replaceAll('ü', 'ue').replaceAll('ß', 'ss')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  if (words.length <= SLUG_MAX) return words
  const cut = words.substring(0, SLUG_MAX + 1).lastIndexOf('-')
  return (cut > 0 ? words.substring(0, cut) : words.substring(0, SLUG_MAX)).replace(/^-+|-+$/g, '')
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

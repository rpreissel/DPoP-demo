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
export const REGISTER_TEXTS = '/mock-stammdaten/texts'

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
  return wording.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (placeholder, name: string) => {
    const nested = ref.texts?.[name]
    if (nested) return nested.map((t) => resolveText(t, base)).join(', ')
    return ref.args?.[name] ?? placeholder
  })
}

/** For tests: forget loaded bundles. */
export function resetTexts() {
  bundles.clear()
}

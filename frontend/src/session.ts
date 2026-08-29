const STORAGE_KEY = 'dpop-demo-channel-session-id'

/**
 * The app must remember its own channelSessionId to resume (docs/02-domaenenmodell.md #3):
 * the DPoP key only proves which device this is, it is never a lookup key for a session.
 * Separate storage from the DPoP key on purpose - losing this (but keeping the key) is exactly
 * the "forgot the sessionId" case that legitimately starts a new session.
 */
export function loadChannelSessionId(): string | null {
  return localStorage.getItem(STORAGE_KEY)
}

export function storeChannelSessionId(channelSessionId: string): void {
  localStorage.setItem(STORAGE_KEY, channelSessionId)
}

export function forgetChannelSessionId(): void {
  localStorage.removeItem(STORAGE_KEY)
}

const AVAILABLE_TOOLS_KEY = 'dpop-demo-available-tools'

/**
 * Which toolIds this client declared as available (docs/03-tool-architektur.md, availability) -
 * remembered per browser so the choice survives a reload instead of always resetting to "every
 * known tool". `null` means "nothing stored yet" (first visit), distinct from an empty array
 * (deliberately deselected everything) - the caller decides the actual default (`knownToolIds`).
 */
export function loadAvailableTools(): string[] | null {
  const raw = localStorage.getItem(AVAILABLE_TOOLS_KEY)
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : null
  } catch {
    return null
  }
}

export function storeAvailableTools(toolIds: string[]): void {
  localStorage.setItem(AVAILABLE_TOOLS_KEY, JSON.stringify(toolIds))
}

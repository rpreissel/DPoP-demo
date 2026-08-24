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

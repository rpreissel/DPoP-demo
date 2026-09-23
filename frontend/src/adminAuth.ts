/**
 * The admin page's login (AdminSecurityConfig: HTTP Basic on /orchestrator/admin/**). Kept for the
 * browser tab only (sessionStorage), so closing the tab logs out - and wrapped in try/catch
 * because storage may be unavailable (private mode), in which case the login simply lasts until
 * the next reload.
 */
const KEY = 'dpop-demo-admin-auth'
let memory: string | null = null
const listeners = new Set<() => void>()

export const ADMIN_PATH = '/orchestrator/admin'

export function adminAuthHeader(): string | null {
  try {
    return sessionStorage.getItem(KEY) ?? memory
  } catch {
    return memory
  }
}

export function setAdminCredentials(username: string, password: string): void {
  memory = `Basic ${btoa(`${username}:${password}`)}`
  try {
    sessionStorage.setItem(KEY, memory)
  } catch {
    // storage unavailable - the in-memory copy still works until reload
  }
}

export function clearAdminCredentials(): void {
  memory = null
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // nothing stored
  }
  listeners.forEach((listener) => listener())
}

/** Called when the server rejected the credentials (401) - the admin page shows its login form again. */
export function onAdminLoggedOut(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

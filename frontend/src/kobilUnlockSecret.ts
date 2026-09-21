/**
 * The app's half of the KOBIL credential: a secret handed out once at setup, which the backend
 * later demands before it releases the PIN.
 *
 * On a real device this would sit in the keystore behind a biometric prompt, so that presenting it
 * requires a fingerprint. Here it sits in `localStorage` behind a simulated prompt, the same
 * honesty deviceKey.ts's demo gate already declares: what the server verifies is possession of the
 * secret, never how the user was asked for it.
 *
 * Keyed by the KOBIL user id, not globally: one credential per installation per account, so a
 * second setup must not silently overwrite the first one's secret.
 */

const KEY_PREFIX = 'kobil-unlock-secret:'

export function storeUnlockSecret(kobilUserId: string, secret: string): void {
  localStorage.setItem(KEY_PREFIX + kobilUserId, secret)
}

export function loadUnlockSecret(kobilUserId: string): string | null {
  return localStorage.getItem(KEY_PREFIX + kobilUserId)
}

import { forgetAllUnlockSecrets } from '../../kobilUnlockSecret'
import type { BoundCredentialView } from '../../types'

const KOBIL_METHOD = 'kobil'

/**
 * Drops this browser's KOBIL unlock secrets once the backend no longer lists a kobil binding for
 * this device.
 *
 * Lives in the tool's own folder, not in the app shell: which method name matters, and what
 * "local data" even means for it, is this module's business - the shell only knows that something
 * may need doing when the device's bindings change. Server-side, a rebind already revoked the
 * credential (docs/09-dpop.md); this is the half no backend can do for us, and it matters here
 * more than for `device` because what lies around is a secret, not merely an unusable key.
 */
export function dropStaleKobilData(boundCredentials: BoundCredentialView[] | undefined): void {
  const stillBound = (boundCredentials ?? []).some((credential) => credential.method === KOBIL_METHOD)
  if (!stillBound) forgetAllUnlockSecrets()
}

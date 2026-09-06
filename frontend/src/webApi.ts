import type { JourneyLogResponse } from './types'

/** Swagger UI isn't proxied by the vite dev server (only /orchestrator is) - matches App.tsx's own BACKEND_ORIGIN. */
const BACKEND_ORIGIN = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin

/**
 * Demo-only read path (`KcMeController`'s own doc, `application-keycloak.yml`'s `kc.oidc`
 * comment): the browser's own real Keycloak AccessToken, presented directly as Bearer auth - a
 * relaxation for this one debug view only, not how the production kc-facade traffic works.
 */
export async function getWebJourneyLog(accessToken: string): Promise<JourneyLogResponse> {
  const response = await fetch(`${BACKEND_ORIGIN}/orchestrator/api/v1/kc/me/journey-log`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  const json = await response.json()
  if (!response.ok) {
    throw new Error(json.message ?? `Journey-Log-Abruf fehlgeschlagen (${response.status})`)
  }
  return json as JourneyLogResponse
}

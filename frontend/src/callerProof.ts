import type { DpopKeyPair } from './dpop'
import type { KcSigningKey } from './kcSigning'

/**
 * Which of the two facades' proof mechanisms a tool step's own `submitViaPatch` should sign with
 * (docs/05-api.md Abschnitt 3) - the App channel's DPoP key, or the Mock-Keycloak's
 * peer-auth signing key plus the kc-anchor it's acting for. One `ToolRenderContext` field
 * (`proof`) instead of two mutually-exclusive optional ones, so a tool module can't be called with
 * neither/both by mistake.
 */
export type CallerProof =
  | { kind: 'dpop'; dpop: DpopKeyPair }
  | { kind: 'kc'; key: KcSigningKey; anchor: { kcAuthSessionId?: string; kcSessionId?: string } }

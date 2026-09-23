import type { DpopKeyPair } from './dpop'

/**
 * What a tool step's own `submitViaPatch` signs with: the App channel's DPoP key. The Web channel
 * never renders orchestrator tools in this frontend - its tool steps run inside Keycloak's own
 * login theme, signed there by the extension's peer-auth key (docs/05-api.md Abschnitt 3).
 */
export type CallerProof = { kind: 'dpop'; dpop: DpopKeyPair }

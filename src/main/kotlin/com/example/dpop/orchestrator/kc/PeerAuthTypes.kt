package com.example.dpop.orchestrator.kc

import java.time.Instant

/**
 * A verified peer-auth assertion (docs/12-entscheidungen.md ADR-7, docs/02-domaenenmodell.md Abschnitt 1) - proves "this really is
 * Keycloak, acting for this specific channel", never who the end user is. [channelAnchor] is
 * always the calling flow run's own `channelSessionId` - unique per flow run, so two concurrent
 * flows sharing the same underlying SSO session (e.g. two tabs stepping up at once) never share an
 * anchor value. Deliberately NOT Keycloak's actual, durable `UserSessionModel` id - that value is
 * carried separately, only where RestoreData needs it (see [com.example.dpop.orchestrator.api.v1.kc.KcChannelService]),
 * never as this claim.
 */
data class PeerAuthAssertion(
    val jti: String,
    val issuedAt: Instant,
    val channelAnchor: String,
    val subject: String?
)

class PeerAuthValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

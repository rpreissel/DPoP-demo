package com.example.dpop.orchestrator.kc

import java.time.Instant

/**
 * A verified peer-auth assertion (docs/ideen/web-keycloak-kanal.md #3) - proves "this really is
 * Keycloak, acting for this specific kc-Session", never who the end user is. [kcSessionId] is the
 * (eventual) `UserSessionModel` id regardless of whether that session exists yet - see
 * [com.example.dpop.orchestrator.session.ChannelSession.kcSessionId]'s own doc for why the
 * initial-login and step-up cases don't need separate fields here.
 */
data class PeerAuthAssertion(
    val jti: String,
    val issuedAt: Instant,
    val kcSessionId: String,
    val subject: String?
)

class PeerAuthValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

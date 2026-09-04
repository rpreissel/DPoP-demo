package com.example.dpop.orchestrator.kc

import java.time.Instant

/**
 * A verified peer-auth assertion (docs/ideen/web-keycloak-kanal.md #3) - proves "this really is
 * Keycloak, acting for this specific kc-Auth-/User-Session", never who the end user is. Exactly
 * one of [kcAuthSessionId] (initial login, no `sub` yet) or [kcSessionId] (step-up, `sub`
 * vorhanden) is set, matching which of the two Web-Kanal cases this request concerns.
 */
data class PeerAuthAssertion(
    val jti: String,
    val issuedAt: Instant,
    val kcAuthSessionId: String?,
    val kcSessionId: String?,
    val subject: String?
)

class PeerAuthValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

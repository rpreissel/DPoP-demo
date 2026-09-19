package com.example.dpop.account

import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.TrustLevel
import java.time.Instant

data class AuthMethodView(
    val id: String?,
    val method: String,
    val active: Boolean,
    val createdAt: Instant?,
    val enrolledUnderAcr: String?,
    val details: Map<String, Any?>?,
    val enrollmentRef: EnrollmentRef,
    val label: String? = null
)

data class AccountProfile(
    val accountId: Long,
    /** Null for an account that was never identified - a deliberate, potentially permanent state (docs/04-orchestrierung.md, REGISTER "Enrollment zuerst"), not a short-lived gap. */
    val personId: Long?,
    val authenticationMethods: List<AuthMethodView>,
    /** The account's EMAIL anchor, i.e. its normalized form. */
    val email: String? = null,
    val emailConfirmedAt: Instant? = null,
    /**
     * Which attributes this account has established, each at the highest [TrustLevel] a
     * non-retracted claim carries (ADR-12). What `ToolDescriptor.requires` is checked against -
     * see `DefaultAuthPolicy.requiresSatisfied`.
     */
    val establishedClaims: Map<AttributeType, TrustLevel> = emptyMap()
) {
    /** Every active entry, including multiple instances of the same method (e.g. several devices) - callers needing just names use `.map { it.method }`. */
    val activeAuthenticationMethods: List<AuthMethodView>
        get() = authenticationMethods.filter { it.active }

    /**
     * Anchor-derived shorthand, kept because Keycloak's own `emailVerified` mirrors it. Agrees
     * with `establishedClaims[EMAIL]` by construction: a retraction deletes the anchor row too.
     */
    val emailConfirmed: Boolean
        get() = emailConfirmedAt != null
}

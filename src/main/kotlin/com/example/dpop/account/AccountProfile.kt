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

    /**
     * No person binding yet (ADR-10) - the account may still ADOPT a freshly attested identity,
     * because there is no second identity on it that a new attestation could silently mix with.
     * An identified account may not: binding someone else's eID claims onto it would merge two
     * people, which is why `JourneyActionExecutor.performIdentified` refuses it outright rather
     * than quietly opening a second account.
     */
    val isUnidentified: Boolean
        get() = personId == null

    /**
     * A **provisional** account: [isUnidentified] AND no credential was ever enrolled on it - a
     * placeholder the running journey created for itself, to which nothing durable has ever
     * attached. The one, named rule behind every operation that is allowed to treat an account
     * as disposable:
     *
     * - `JourneyService.deleteIfAbandonedUnidentified` deletes it when its journey is abandoned.
     * - `AccountService.absorbInteressent` lets it YIELD to the account an assignment step
     *   resolves, taking its attestations along (ADR-20).
     *
     * DEACTIVATED instances count as credentials here, deliberately: a revoked instance still
     * owns claim provenance (`account.claim.auth_method_id`, ADR-12) that cannot be carried to
     * another account or thrown away silently. "Never had one" is the rule, not "has none right
     * now" - the weaker reading would make the two operations above lose exactly that history.
     */
    val isProvisional: Boolean
        get() = isUnidentified && authenticationMethods.isEmpty()
}

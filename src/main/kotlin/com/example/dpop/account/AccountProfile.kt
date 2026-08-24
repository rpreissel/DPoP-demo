package com.example.dpop.account

import java.time.Instant

data class AuthMethodView(
    val method: String,
    val active: Boolean,
    val createdAt: Instant?,
    val enrolledUnderAcr: String?,
    val details: Map<String, Any?>?
)

data class IdentificationView(
    val method: String,
    val loa: String?,
    val identifiedAt: Instant?,
    val details: Map<String, Any?>?
)

data class AccountProfile(
    val accountId: Long,
    val personId: Long,
    val identifications: List<IdentificationView>,
    val authenticationMethods: List<AuthMethodView>,
    val email: String? = null,
    val emailConfirmedAt: Instant? = null
) {
    val activeAuthenticationMethods: List<String>
        get() = authenticationMethods.filter { it.active }.map { it.method }.distinct()

    /** Precondition for tools like enroll-password that require a confirmed identifier first (docs/03-tool-architektur.md). */
    val emailConfirmed: Boolean
        get() = emailConfirmedAt != null
}

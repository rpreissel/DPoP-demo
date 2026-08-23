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
    val authenticationMethods: List<AuthMethodView>
) {
    val activeAuthenticationMethods: List<String>
        get() = authenticationMethods.filter { it.active }.map { it.method }.distinct()
}

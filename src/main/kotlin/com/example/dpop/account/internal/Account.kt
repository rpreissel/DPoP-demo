package com.example.dpop.account.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "account")
class Account(
    var personId: Long? = null,
    var createdAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** Durable record of how this account's identity was ever established (docs/06-ablaeufe.md #1). */
    @JdbcTypeCode(SqlTypes.JSON)
    var identifications: MutableList<AccountIdentification> = mutableListOf()

    /** Enrolled 2nd-factor methods, with the level they were enrolled under (capping, docs/04-orchestrierung.md). */
    @JdbcTypeCode(SqlTypes.JSON)
    var authenticationMethods: MutableList<AuthenticationMethod> = mutableListOf()

    /**
     * Direct scalar fields, not routed through the generic authenticationMethods/enrollmentRef
     * indirection - same treatment as [personId]: a single canonical account attribute, not a
     * swappable per-enrollment credential (unlike phoneNumber/username, which stay module-owned).
     */
    var email: String? = null
    var emailConfirmedAt: Instant? = null

    fun addIdentification(identification: AccountIdentification) {
        identifications.add(identification)
    }

    fun addAuthenticationMethod(authenticationMethod: AuthenticationMethod) {
        authenticationMethods.add(authenticationMethod)
    }
}

/** Matches account.authenticationMethods[] in docs/06-ablaeufe.md #1. */
class AuthenticationMethod(
    var method: String? = null,
    var active: Boolean = false,
    var createdAt: Instant? = null,
    /** Level in force when this method was set up; caps what it can ever authenticate to. */
    var enrolledUnderAcr: String? = null,
    var details: Map<String, Any?>? = null
)

/** Matches account.identifications[] in docs/06-ablaeufe.md #1. */
class AccountIdentification(
    var method: String? = null,
    var loa: String? = null,
    var identifiedAt: Instant? = null,
    var details: Map<String, Any?>? = null
)

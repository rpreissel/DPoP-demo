package com.example.dpop.account.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
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

    /**
     * Guards [identifications]/[authenticationMethods] against a lost update, e.g. two
     * concurrent step-up channels for the same account both enrolling a method at once
     * (docs/07-betrieb.md #1) - a stale writer gets `ObjectOptimisticLockingFailureException`,
     * already translated to `409 CONCURRENT_MODIFICATION` by `OrchestratorExceptionHandler`, the
     * same contract every other multi-writer entity in this system already follows
     * (`ChannelSession`, `AuthEvidence`, `AuthJourney`).
     */
    @Version
    var version: Long? = null

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

/**
 * Matches account.authenticationMethods[] in docs/06-ablaeufe.md #1.
 *
 * A `data class`, deliberately - not cosmetic: this is stored via `@JdbcTypeCode(SqlTypes.JSON)`
 * on [Account.authenticationMethods], and Hibernate's dirty-checking for JSON-mapped collections
 * compares the freshly-loaded value against the flushed snapshot via `equals()`. Without a
 * structural `equals()` (the default `Object` identity one a plain `class` gets), two loads of
 * the IDENTICAL persisted content are never `equal`, so Hibernate treats the column as changed on
 * every flush of any transaction that merely loaded the account - issuing a real UPDATE (with a
 * `@Version` bump) for a no-op. That silently turns every unrelated read into a write, which then
 * races with any genuinely concurrent request touching the same account and fails one of them
 * with `ObjectOptimisticLockingFailureException`, even though nothing was actually being changed
 * concurrently. `id`/`label` moved into the constructor for the same reason: a body-declared `var`
 * is invisible to the generated `equals()`/`hashCode()`, which would then wrongly consider two
 * genuinely different instances (e.g. two physical devices) equal whenever their other fields
 * happen to match.
 */
data class AuthenticationMethod(
    var method: String? = null,
    var active: Boolean = false,
    var createdAt: Instant? = null,
    /** Level in force when this method was set up; caps what it can ever authenticate to. */
    var enrolledUnderAcr: String? = null,
    var details: Map<String, Any?>? = null,
    /** Stable per-instance id (UUID, assigned on creation) - the only thing DELETE .../methods/{id} addresses by, since a method NAME (e.g. "device") may have several active instances (docs/03-tool-architektur.md, allowsMultipleInstances). */
    var id: String? = null,
    /** User-chosen label, set only for multi-instance methods (device) - null for singleton methods, which the frontend labels from the method name itself instead. */
    var label: String? = null
)

/**
 * Matches account.identifications[] in docs/06-ablaeufe.md #1. `data class` for the same
 * dirty-checking reason as [AuthenticationMethod]'s own doc - `identifications` is the other
 * `@JdbcTypeCode(SqlTypes.JSON)` collection on [Account].
 */
data class AccountIdentification(
    var method: String? = null,
    var loa: String? = null,
    var identifiedAt: Instant? = null,
    var details: Map<String, Any?>? = null
)

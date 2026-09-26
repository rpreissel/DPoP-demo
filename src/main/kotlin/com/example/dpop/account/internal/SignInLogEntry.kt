package com.example.dpop.account.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

/**
 * What happened to a sign-in (ADR-39, addendum). [detailsVersion] plays the same part as
 * [ChangeType.detailsVersion]: written into every row, raised whenever `SignInLog` changes the keys
 * of that event.
 */
enum class SignInType(val detailsVersion: Int) {
    /** An entry journey (logging in, registering, a peer login) left the channel authenticated. */
    SIGNED_IN(1),

    /** A STEP_UP journey raised an authenticated channel's level. */
    STEPPED_UP(1),

    /** One proof of an existing account failed - a wrong password, TAN, OTP. */
    SIGN_IN_FAILED(1),

    /** That failure tripped the account's brute-force lock. */
    LOCKED_OUT(1),

    /** A session of the account ended on purpose - never a mere expiry. */
    SIGNED_OUT(1),
}

/**
 * One line of the sign-in log. Unlike [ChangeLogEntry] it belongs to the account (foreign key,
 * deleted with it) and lives only months: it answers "who signed in to this account, and how",
 * not what survives a deletion.
 */
@Entity
@Table(schema = "account", name = "sign_in_log")
class SignInLogEntry(
    @Column(name = "account_id", nullable = false, updatable = false)
    val accountId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "sign_in_type", nullable = false, updatable = false, length = 32)
    val signInType: SignInType = SignInType.SIGNED_IN,

    /** The channel it happened on (`APP`, `KEYCLOAK`). */
    @Column(name = "channel", updatable = false, length = 16)
    val channel: String? = null,

    @Column(name = "acr", updatable = false, length = 16)
    val acr: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", updatable = false)
    val details: Map<String, Any?>? = null,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null
}

interface SignInLogRepository : JpaRepository<SignInLogEntry, Long> {
    fun findByAccountIdOrderByOccurredAt(accountId: Long): List<SignInLogEntry>

    /** One retention batch, oldest first (`SignInLogRetention`). */
    @Query("select e.id from SignInLogEntry e where e.occurredAt < :cutoff order by e.occurredAt")
    fun idsOlderThan(cutoff: Instant, pageable: Pageable): List<Long>

    @Modifying
    @Query("delete from SignInLogEntry e where e.id in :ids")
    fun deleteByIdIn(ids: Collection<Long>): Int
}

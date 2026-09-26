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
 * What happened to an account - never a value (ADR-39).
 *
 * [detailsVersion] is written into every row's `details` next to the type, so a reader years later
 * knows which keys to expect. Raise it whenever [ChangeLog] changes the keys of that event - rows
 * already written keep the version they were written with.
 */
enum class ChangeType(val detailsVersion: Int) {
    IDENTIFIED(1),
    ATTRIBUTE_RETRACTED(1),
    METHOD_ADDED(1),
    METHOD_DEACTIVATED(1),
    ACCOUNT_DELETED(1),
    ACCOUNT_ABSORBED(1),
}

/**
 * One line of the change log that outlives the account (ADR-39): that and how something happened,
 * never what. Append-only - nothing updates a row, only [ChangeLogRetention] deletes, and only after the
 * retention period since the account's deletion. No foreign key to the account on purpose.
 *
 * Fixed columns for what every event has; [details] for what only some carry. Which keys exist per
 * event is decided in one place, [ChangeLog] - never by a caller. [details] always names its own
 * `type` and `version` ([ChangeType.detailsVersion]), so a row explains itself without the code
 * that wrote it.
 *
 * The exception are search keys: [lookupKey] and [personId] are what a person is found by, years
 * later and among millions of rows - an indexed column each, never a scan through [details].
 */
@Entity
@Table(schema = "account", name = "change_log")
class ChangeLogEntry(
    @Column(name = "account_id", nullable = false, updatable = false)
    val accountId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 32)
    val changeType: ChangeType = ChangeType.IDENTIFIED,

    /** The method (`sms`, `ident-fsc`) or attribute type (`EMAIL`) the event is about - a name, never a value. */
    @Column(name = "subject", updatable = false, length = 50)
    val subject: String? = null,

    @Column(name = "acr", updatable = false, length = 16)
    val acr: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", updatable = false)
    val details: Map<String, Any?>? = null,

    /** [PersonLookupKey] of the verified name, first name and date of birth - set on `IDENTIFIED` only. */
    @Column(name = "lookup_key", updatable = false, length = 64)
    val lookupKey: String? = null,

    /** The register's person id, when the account had one - set on `IDENTIFIED` only. */
    @Column(name = "person_id", updatable = false, length = 64)
    val personId: String? = null,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null
}

interface ChangeLogRepository : JpaRepository<ChangeLogEntry, Long> {
    fun findByAccountIdOrderByOccurredAt(accountId: Long): List<ChangeLogEntry>

    fun findByAccountIdAndChangeTypeOrderByOccurredAt(accountId: Long, changeType: ChangeType): List<ChangeLogEntry>

    @Query("select distinct e.accountId from ChangeLogEntry e where e.lookupKey = :lookupKey")
    fun accountsWithLookupKey(lookupKey: String): List<Long>

    @Query("select distinct e.accountId from ChangeLogEntry e where e.personId = :personId")
    fun accountsWithPersonId(personId: String): List<Long>

    fun findByAccountIdInOrderByAccountIdAscOccurredAtAsc(accountIds: Collection<Long>): List<ChangeLogEntry>

    @Query("select e.accountId from ChangeLogEntry e where e.changeType = com.example.dpop.account.internal.ChangeType.ACCOUNT_DELETED and e.occurredAt < :cutoff")
    fun accountsDeletedBefore(cutoff: Instant, pageable: Pageable): List<Long>

    @Modifying
    @Query("delete from ChangeLogEntry e where e.accountId in :accountIds")
    fun deleteByAccountIdIn(accountIds: Collection<Long>): Int
}

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
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

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

    @Query("select e.accountId from ChangeLogEntry e where e.changeType = com.example.dpop.account.internal.ChangeType.ACCOUNT_DELETED and e.occurredAt < :cutoff")
    fun accountsDeletedBefore(cutoff: Instant, pageable: Pageable): List<Long>

    @Modifying
    @Query("delete from ChangeLogEntry e where e.accountId in :accountIds")
    fun deleteByAccountIdIn(accountIds: Collection<Long>): Int
}

/** Why a method stopped being active. */
enum class MethodDeactivationReason {
    /** A singleton method was enrolled again; the new instance replaces the old one. */
    REPLACED,

    /** The account holder removed it (MANAGE_METHODS). */
    REMOVED_BY_HOLDER,
}

/**
 * The one way anything writes to the change log - one function per event, so the keys an event
 * carries are fixed here and nowhere else. Joins the caller's transaction: an event exists exactly
 * when its change does.
 */
@Component
class ChangeLog(private val repository: ChangeLogRepository) {

    /**
     * An identification run. Of what the tool reported, only the references are kept, by name:
     * where to ask about the case, which procedure in which version, and a hash of what was seen.
     * Anything else - a document number above all, which may not be kept (§ 20 PAuswG) - is
     * dropped here, whatever a tool puts into its report.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun identified(accountId: Long, method: String, acr: String?, role: String?, report: Map<String, Any?>) =
        record(
            accountId, ChangeType.IDENTIFIED, subject = method, acr = acr,
            details = mapOf("role" to role) + IDENTIFICATION_REFERENCE_KEYS.associateWith { report[it]?.toString() },
        )

    /** A method was added: under which proofs of the session (amr) and on which channel. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun methodAdded(accountId: Long, method: String, acr: String?, amr: List<String>, channel: String?, at: Instant) =
        record(accountId, ChangeType.METHOD_ADDED, subject = method, acr = acr, at = at, details = mapOf("amr" to amr, "channel" to channel))

    @Transactional(propagation = Propagation.MANDATORY)
    fun methodDeactivated(accountId: Long, method: String?, reason: MethodDeactivationReason, at: Instant) =
        record(accountId, ChangeType.METHOD_DEACTIVATED, subject = method, at = at, details = mapOf("reason" to reason.name))

    /** An attribute was withdrawn - by whom (trust anchor) and why, never its value. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun attributeRetracted(accountId: Long, attributeType: String?, trustAnchor: String?, reason: String?, at: Instant) =
        record(
            accountId, ChangeType.ATTRIBUTE_RETRACTED, subject = attributeType, at = at,
            details = mapOf("trustAnchor" to trustAnchor, "reason" to reason),
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun accountDeleted(accountId: Long) = record(accountId, ChangeType.ACCOUNT_DELETED)

    /**
     * [into] took over the provisional account [from] (ADR-20). The identifications [from] had are
     * now [into]'s - the proof of identity belongs to the account the person ended up in. Copied,
     * not moved: the trail stays append-only; each copy names where it came from.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun accountAbsorbed(into: Long, from: Long) {
        repository.findByAccountIdAndChangeTypeOrderByOccurredAt(from, ChangeType.IDENTIFIED).forEach { e ->
            // Keeps the version the original was written with - its keys are that version's.
            repository.save(
                ChangeLogEntry(
                    accountId = into, changeType = ChangeType.IDENTIFIED, subject = e.subject, acr = e.acr,
                    details = e.details.orEmpty() + mapOf("carriedFromAccountId" to from), occurredAt = e.occurredAt,
                )
            )
        }
        record(into, ChangeType.ACCOUNT_ABSORBED, details = mapOf("absorbedAccountId" to from))
    }

    private fun record(
        accountId: Long, type: ChangeType, subject: String? = null, acr: String? = null,
        at: Instant = Instant.now(), details: Map<String, Any?> = emptyMap(),
    ) {
        repository.save(
            ChangeLogEntry(
                accountId = accountId, changeType = type, subject = subject, acr = acr,
                details = mapOf("type" to type.name, "version" to type.detailsVersion) + details.filterValues { it != null },
                occurredAt = at,
            )
        )
    }

    private companion object {
        /** The only keys of an identification report that reach the trail (see [identified]). */
        val IDENTIFICATION_REFERENCE_KEYS = listOf("provider", "providerTxId", "procedure", "methodVersion", "evidenceHash")
    }
}

/**
 * Deletes the change log of accounts deleted longer than `account.change-log.retention-years` ago
 * (ADR-39; default 10 years, to be confirmed by data protection). In batches - with millions of
 * accounts, a year's deletions can be many rows.
 */
@Component
class ChangeLogRetention(
    private val repository: ChangeLogRepository,
    @Value("\${account.change-log.retention-years:10}") private val retentionYears: Long,
) {
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    @Transactional
    fun sweep() {
        purge(Instant.now())
    }

    @Transactional
    fun purge(now: Instant): Int {
        val cutoff = now.atZone(java.time.ZoneOffset.UTC).minusYears(retentionYears).toInstant()
        var total = 0
        while (true) {
            val accounts = repository.accountsDeletedBefore(cutoff, Pageable.ofSize(BATCH))
            if (accounts.isEmpty()) return total
            total += repository.deleteByAccountIdIn(accounts)
        }
    }

    private companion object {
        const val BATCH = 500
    }
}

package com.example.dpop.account.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
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

/** What happened to an account - never a value (ADR-39). */
enum class AuditEventType { IDENTIFIED, ATTRIBUTE_RETRACTED, METHOD_ADDED, METHOD_DEACTIVATED, ACCOUNT_DELETED, ACCOUNT_ABSORBED }

/**
 * One line of the audit trail that outlives the account (ADR-39): that and how something happened,
 * never what. Append-only - nothing updates a row, only [AuditRetention] deletes, and only after the
 * retention period since the account's deletion. No foreign key to the account on purpose.
 */
@Entity
@Table(schema = "account", name = "audit_event")
class AuditEvent(
    @Column(name = "account_id", nullable = false, updatable = false)
    val accountId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    val eventType: AuditEventType = AuditEventType.IDENTIFIED,

    /** The method (`sms`, `ident-fsc`) or attribute type (`EMAIL`) the event is about - a name, never a value. */
    @Column(name = "subject", updatable = false, length = 50)
    val subject: String? = null,

    @Column(name = "acr", updatable = false, length = 16)
    val acr: String? = null,

    /** Who or what caused it: a trust anchor (`ACCOUNT_HOLDER`), a reason code, the identification role, or the other account of an absorption. */
    @Column(name = "source", updatable = false, length = 64)
    val source: String? = null,

    /**
     * Where to ask about it: for an identification the provider, its transaction id and the
     * procedure version (`provider=…;tx=…;version=…`) - what makes a flawed procedure traceable to
     * the accounts it identified, and a single case checkable with the provider. Never a value of
     * the person.
     */
    @Column(name = "reference", updatable = false, length = 255)
    val reference: String? = null,

    /** A hash of what the identification saw - proves it without keeping it. */
    @Column(name = "evidence_hash", updatable = false, length = 128)
    val evidenceHash: String? = null,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null
}

interface AuditEventRepository : JpaRepository<AuditEvent, Long> {
    fun findByAccountIdOrderByOccurredAt(accountId: Long): List<AuditEvent>

    fun findByAccountIdAndEventTypeOrderByOccurredAt(accountId: Long, eventType: AuditEventType): List<AuditEvent>

    @Query("select e.accountId from AuditEvent e where e.eventType = com.example.dpop.account.internal.AuditEventType.ACCOUNT_DELETED and e.occurredAt < :cutoff")
    fun accountsDeletedBefore(cutoff: Instant, pageable: Pageable): List<Long>

    @Modifying
    @Query("delete from AuditEvent e where e.accountId in :accountIds")
    fun deleteByAccountIdIn(accountIds: Collection<Long>): Int
}

/** The one way anything writes to the audit trail. Joins the caller's transaction - an event exists exactly when its change does. */
@Component
class AuditLog(private val repository: AuditEventRepository) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        accountId: Long, type: AuditEventType, subject: String? = null, acr: String? = null, source: String? = null,
        at: Instant = Instant.now(), reference: String? = null, evidenceHash: String? = null,
    ) {
        repository.save(
            AuditEvent(
                accountId = accountId, eventType = type, subject = subject, acr = acr, source = source?.take(64),
                reference = reference?.take(255), evidenceHash = evidenceHash?.take(128), occurredAt = at,
            )
        )
    }

    /**
     * The identifications [from] had, now [into]'s (an absorbed provisional account, ADR-20) - the
     * proof of identity belongs to the account the person ended up in. Copied, not moved: the
     * trail stays append-only; the copies name where they came from.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun carryIdentifications(from: Long, into: Long) {
        repository.findByAccountIdAndEventTypeOrderByOccurredAt(from, AuditEventType.IDENTIFIED).forEach { e ->
            repository.save(
                AuditEvent(
                    accountId = into, eventType = AuditEventType.IDENTIFIED, subject = e.subject, acr = e.acr,
                    source = listOfNotNull(e.source, "account:$from").joinToString("|").take(64),
                    reference = e.reference, evidenceHash = e.evidenceHash, occurredAt = e.occurredAt,
                )
            )
        }
    }
}

/**
 * Deletes the audit trail of accounts deleted longer than `account.audit.retention-years` ago
 * (ADR-39; default 10 years, to be confirmed by data protection). In batches - with millions of
 * accounts, a year's deletions can be many rows.
 */
@Component
class AuditRetention(
    private val repository: AuditEventRepository,
    @Value("\${account.audit.retention-years:10}") private val retentionYears: Long,
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

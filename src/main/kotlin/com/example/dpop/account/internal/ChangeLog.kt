package com.example.dpop.account.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

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
 *
 * Internal on purpose: only the account itself changes the account, so only [com.example.dpop.account.AccountService]
 * writes here - unlike [com.example.dpop.account.SignInLog], which the orchestrator writes, since
 * sign-ins happen there.
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
    fun identified(
        accountId: Long, method: String, acr: String?, role: String?, report: Map<String, Any?>,
        lookupKey: String?, personId: String?,
    ) =
        record(
            accountId, ChangeType.IDENTIFIED, subject = method, acr = acr,
            details = mapOf("role" to role) + IDENTIFICATION_REFERENCE_KEYS.associateWith { report[it]?.toString() },
            lookupKey = lookupKey, personId = personId,
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
                    lookupKey = e.lookupKey, personId = e.personId,
                )
            )
        }
        record(into, ChangeType.ACCOUNT_ABSORBED, details = mapOf("absorbedAccountId" to from))
    }

    private fun record(
        accountId: Long, type: ChangeType, subject: String? = null, acr: String? = null,
        at: Instant = Instant.now(), details: Map<String, Any?> = emptyMap(),
        lookupKey: String? = null, personId: String? = null,
    ) {
        repository.save(
            ChangeLogEntry(
                accountId = accountId, changeType = type, subject = subject, acr = acr,
                details = mapOf("type" to type.name, "version" to type.detailsVersion) + details.filterValues { it != null },
                occurredAt = at, lookupKey = lookupKey, personId = personId,
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
 * (ADR-39; default 10 years, to be confirmed by data protection). In batches, each in its own
 * transaction - with millions of accounts, a year's deletions can be many rows (like
 * `SignInLogRetention`, review 2026-09-26, B-7).
 */
@Component
class ChangeLogRetention(
    private val repository: ChangeLogRepository,
    private val transactions: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${account.change-log.retention-years:10}") private val retentionYears: Long,
) {
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    fun sweep() {
        val deleted = purge(Instant.now())
        meterRegistry.counter("dpop.retention.deleted", "table", "change_log").increment(deleted.toDouble())
    }

    fun purge(now: Instant): Int {
        val cutoff = now.atZone(java.time.ZoneOffset.UTC).minusYears(retentionYears).toInstant()
        var total = 0
        while (true) {
            val deleted = transactions.execute {
                val accounts = repository.accountsDeletedBefore(cutoff, Pageable.ofSize(BATCH))
                if (accounts.isEmpty()) 0 else repository.deleteByAccountIdIn(accounts)
            } ?: 0
            if (deleted == 0) return total
            total += deleted
        }
    }

    private companion object {
        const val BATCH = 500
    }
}

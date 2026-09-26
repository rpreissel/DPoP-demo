package com.example.dpop.account

import io.micrometer.core.instrument.MeterRegistry
import com.example.dpop.account.infrastructure.AccountRepository
import com.example.dpop.account.infrastructure.SignInType
import com.example.dpop.account.infrastructure.SignInLogEntry
import com.example.dpop.account.infrastructure.SignInLogRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneOffset

/** One line of the sign-in log, as [SignInLog.of] hands it out. */
data class SignInRecord(
    val signInType: String,
    val channel: String?,
    val acr: String?,
    val details: Map<String, Any?>,
    val occurredAt: Instant,
)

/**
 * The account's sign-in log (ADR-39, addendum): signed in, stepped up, a failed proof, a lockout,
 * signed out. The one way anything writes it - one function per event, so the keys each event
 * carries are fixed here. Joins the caller's transaction where there is one: a sign-in is logged
 * exactly when it happens.
 *
 * Kept `account.sign-in-log.retention-months` ([SignInLogRetention]) and deleted with the account:
 * login history is behaviour, not proof - what outlives a deletion is the change log alone.
 *
 * Public, unlike the change log's writer: sign-ins happen in the orchestrator, so it writes here.
 */
@Service
class SignInLog(
    private val repository: SignInLogRepository,
    private val accounts: AccountRepository,
) {

    /** An entry journey (logging in, registering, a peer login) left the channel authenticated. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun signedIn(accountId: Long, channel: String?, acr: String?, amr: List<String>, intent: String) =
        record(accountId, SignInType.SIGNED_IN, channel, acr, mapOf("amr" to amr, "intent" to intent))

    /** A STEP_UP journey raised the level of an authenticated channel. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun steppedUp(accountId: Long, channel: String?, acr: String?, amr: List<String>) =
        record(accountId, SignInType.STEPPED_UP, channel, acr, mapOf("amr" to amr))

    /** One proof of [accountId] failed with [method] - the account was known, the proof was wrong. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun signInFailed(accountId: Long, channel: String?, method: String) =
        record(accountId, SignInType.SIGN_IN_FAILED, channel, details = mapOf("method" to method))

    /** That failure locked the account until [lockedUntil]. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun lockedOut(accountId: Long, channel: String?, lockedUntil: Instant) =
        record(accountId, SignInType.LOCKED_OUT, channel, details = mapOf("lockedUntil" to lockedUntil.toString()))

    /** A session of [accountId] ended on purpose. [endedBy]: `HOLDER` (asked for it) or `IDENTITY_PROVIDER` (Keycloak ended it). */
    @Transactional(propagation = Propagation.REQUIRED)
    fun signedOut(accountId: Long, channel: String?, endedBy: String) =
        record(accountId, SignInType.SIGNED_OUT, channel, details = mapOf("endedBy" to endedBy))

    @Transactional(readOnly = true)
    fun of(accountId: Long): List<SignInRecord> =
        repository.findByAccountIdOrderByOccurredAt(accountId).map {
            SignInRecord(it.signInType.name, it.channel, it.acr, it.details.orEmpty(), it.occurredAt)
        }

    private fun record(
        accountId: Long, type: SignInType, channel: String?, acr: String? = null, details: Map<String, Any?> = emptyMap(),
    ) {
        // The log goes with the account - a session ending right after its account was deleted
        // (DELETE_ACCOUNT logs the channel out last) has nothing left to log against.
        if (!accounts.existsById(accountId)) return
        repository.save(
            SignInLogEntry(
                accountId = accountId, signInType = type, channel = channel, acr = acr,
                details = mapOf("type" to type.name, "version" to type.detailsVersion) + details.filterValues { it != null },
            )
        )
    }
}

/**
 * Deletes sign-in log lines older than `account.sign-in-log.retention-months` (6 by default) - daily.
 *
 * In batches, each in its own transaction (review 2026-09-26, B-7): one sign-in line per login makes
 * this a table of many millions, and a single delete over a month of them would hold one
 * transaction - and its locks and undo - for all of it.
 */
@Component
class SignInLogRetention(
    private val repository: SignInLogRepository,
    private val transactions: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${account.sign-in-log.retention-months:6}") private val retentionMonths: Long,
) {
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    fun sweep() {
        val deleted = purge(Instant.now())
        meterRegistry.counter("dpop.retention.deleted", "table", "sign_in_log").increment(deleted.toDouble())
    }

    fun purge(now: Instant): Int {
        val cutoff = now.atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant()
        var total = 0
        while (true) {
            val deleted = transactions.execute {
                val ids = repository.idsOlderThan(cutoff, Pageable.ofSize(BATCH))
                if (ids.isEmpty()) 0 else repository.deleteByIdIn(ids)
            } ?: 0
            if (deleted == 0) return total
            total += deleted
        }
    }

    private companion object {
        const val BATCH = 500
    }
}

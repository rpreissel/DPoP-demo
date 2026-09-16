package com.example.dpop.account.internal

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Creates a missing `Account` row for a `personId`, in its own transaction and nowhere else - the
 * find-then-create race between two concurrent step-up channels is closed DB-side by
 * `ux_account_person_id` (V32), but the loser must see that as "the account already exists," not
 * a server error (C4, docs/13-review-domaenen-db-modell.md).
 *
 * Its own bean rather than a private method on `AccountService`, for the same two reasons
 * `AttemptThrottleRowInitializer` already documents: a `REQUIRES_NEW` method called from within
 * the same bean would bypass the Spring proxy and silently run in the caller's transaction; and
 * the constraint violation a concurrent creator provokes must not poison that caller's
 * transaction (on some databases a failed statement aborts the whole transaction, so catching the
 * exception in place would not be enough).
 */
@Component
class AccountRaceSafeCreator(private val accountRepository: AccountRepository) {

    /**
     * Idempotent and race-tolerant: the loser of a concurrent creation sees the unique violation
     * its rival caused and treats it as "already exists," because the only thing the caller needs
     * is that a row for [personId] exists before it re-reads.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createIfAbsent(personId: Long): Boolean {
        if (accountRepository.findByPersonId(personId) != null) return false
        return try {
            accountRepository.saveAndFlush(Account(personId, Instant.now()))
            true
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }
}

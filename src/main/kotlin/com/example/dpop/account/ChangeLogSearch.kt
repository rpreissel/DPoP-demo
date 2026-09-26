package com.example.dpop.account

import com.example.dpop.account.internal.ChangeLogRepository
import com.example.dpop.account.internal.PersonLookupKey
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/** One line of the change log, as the search hands it out. */
data class ChangeLogRecord(
    val accountId: Long,
    val changeType: String,
    val subject: String?,
    val acr: String?,
    val details: Map<String, Any?>,
    val occurredAt: Instant,
)

/**
 * Finds a person in the change log (ADR-39) - also, and above all, after their account was deleted,
 * when nothing else about them is left. The typical request: someone says their account was taken
 * over, and all they can give is name, first name and date of birth.
 *
 * Returns the whole trail of every account that was ever identified as that person. Several
 * accounts can match - the same person twice, or two people sharing all three values; the
 * provider reference in each `IDENTIFIED` entry tells them apart.
 */
@Service
class ChangeLogSearch(
    private val repository: ChangeLogRepository,
    private val personLookupKey: PersonLookupKey,
) {
    @Transactional(readOnly = true)
    fun byPerson(name: String, vorname: String, geburtsdatum: LocalDate): List<ChangeLogRecord> =
        personLookupKey.of(name, vorname, geburtsdatum)
            ?.let { trailOf(repository.accountsWithLookupKey(it)) }
            .orEmpty()

    /** The same for a register person id, when the person is known to the register. */
    @Transactional(readOnly = true)
    fun byPersonId(personId: String): List<ChangeLogRecord> =
        trailOf(repository.accountsWithPersonId(personId.trim()))

    private fun trailOf(accountIds: List<Long>): List<ChangeLogRecord> =
        if (accountIds.isEmpty()) emptyList()
        else repository.findByAccountIdInOrderByAccountIdAscOccurredAtAsc(accountIds).map {
            ChangeLogRecord(it.accountId, it.changeType.name, it.subject, it.acr, it.details.orEmpty(), it.occurredAt)
        }
}

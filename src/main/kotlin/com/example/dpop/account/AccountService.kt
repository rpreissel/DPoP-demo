package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountIdentification
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.account.internal.AuthenticationMethod
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AccountService(private val accountRepository: AccountRepository) {

    /** Only the orchestrator calls this, right after Completed.Identified (docs/04-orchestrierung.md). */
    @Transactional
    fun findOrCreateAccount(personId: Long): AccountProfile {
        val account = accountRepository.findByPersonId(personId) ?: Account(personId, Instant.now())
        return toProfile(accountRepository.save(account))
    }

    @Transactional
    fun addIdentification(
        accountId: Long,
        method: String,
        loa: String?,
        details: Map<String, Any?>?
    ): AccountProfile {
        val account = getOrThrow(accountId)
        account.addIdentification(AccountIdentification(method, loa, Instant.now(), details))
        return toProfile(accountRepository.save(account))
    }

    @Transactional
    fun addAuthenticationMethod(
        accountId: Long,
        method: String,
        enrollmentRef: EnrollmentRef,
        enrolledUnderAcr: String?,
        details: Map<String, Any?>
    ): AccountProfile {
        val account = getOrThrow(accountId)
        val mergedDetails = details + mapOf("enrollmentRef" to mapOf("type" to enrollmentRef.type, "id" to enrollmentRef.id))
        account.addAuthenticationMethod(
            AuthenticationMethod(method, true, Instant.now(), enrolledUnderAcr, mergedDetails)
        )
        return toProfile(accountRepository.save(account))
    }

    @Transactional(readOnly = true)
    fun findAccount(accountId: Long): AccountProfile? =
        accountRepository.findByIdOrNull(accountId)?.let { toProfile(it) }

    @Transactional(readOnly = true)
    fun findActiveMethod(accountId: Long, method: String): AuthMethodView? =
        findAccount(accountId)?.authenticationMethods?.firstOrNull { it.active && it.method == method }

    @Transactional(readOnly = true)
    fun findActiveMethods(accountId: Long): List<AuthMethodView> =
        findAccount(accountId)?.authenticationMethods?.filter { it.active } ?: emptyList()

    private fun getOrThrow(accountId: Long): Account =
        accountRepository.findByIdOrNull(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")

    private fun toProfile(account: Account): AccountProfile = AccountProfile(
        accountId = requireNotNull(account.id) { "Account has no id" },
        personId = requireNotNull(account.personId) { "Account has no personId" },
        identifications = account.identifications.map {
            IdentificationView(it.method.orEmpty(), it.loa, it.identifiedAt, it.details)
        },
        authenticationMethods = account.authenticationMethods.map {
            AuthMethodView(it.method.orEmpty(), it.active, it.createdAt, it.enrolledUnderAcr, it.details)
        }
    )
}

package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountIdentification
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.account.internal.AuthenticationMethod
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AccountService(private val accountRepository: AccountRepository) {

    fun manageAccount(): String = "account: account managed"

    @Transactional
    fun identifyAccount(
        personId: Long,
        identificationMethod: String,
        identificationQuality: String,
        registrationSessionId: UUID,
        details: Map<String, Any>
    ): AccountProfile {
        val account: Account = accountRepository.findByPersonId(personId)
            ?: Account(personId, Instant.now())

        account.addIdentification(
            AccountIdentification(
                identificationMethod,
                identificationQuality,
                Instant.now(),
                registrationSessionId.toString(),
                details
            )
        )
        return toProfile(accountRepository.save(account))
    }

    @Transactional
    fun createAccount(
        personId: Long,
        identificationMethod: String,
        identificationQuality: String,
        registrationSessionId: UUID,
        details: Map<String, Any>
    ): AccountProfile = identifyAccount(personId, identificationMethod, identificationQuality, registrationSessionId, details)

    @Transactional
    fun addAuthenticationMethod(
        accountId: Long,
        method: String,
        active: Boolean,
        details: Map<String, Any>
    ): AccountProfile {
        val account = accountRepository.findByIdOrNull(accountId)
            ?: throw IllegalArgumentException("Account not found")
        account.addAuthenticationMethod(AuthenticationMethod(method, active, Instant.now(), details))
        return toProfile(accountRepository.save(account))
    }

    @Transactional(readOnly = true)
    fun findByPersonId(personId: Long): AccountProfile? =
        accountRepository.findByPersonId(personId)?.let { toProfile(it) }

    @Transactional(readOnly = true)
    fun findAccountProfile(accountId: Long): AccountProfile? =
        accountRepository.findByIdOrNull(accountId)?.let { toProfile(it) }

    @Transactional(readOnly = true)
    fun hasActiveAuthenticationMethod(accountId: Long): Boolean =
        accountRepository.findByIdOrNull(accountId)
            ?.authenticationMethods?.any { it.active }
            ?: false

    @Transactional(readOnly = true)
    fun findActiveSmsEnrollmentId(accountId: Long): Long? {
        return accountRepository.findByIdOrNull(accountId)
            ?.authenticationMethods
            ?.asSequence()
            ?.filter { it.active }
            ?.filter { it.method == "sms" }
            ?.mapNotNull { it.details }
            ?.mapNotNull { it["enrollmentRef"] ?: it["enrollmentId"] }
            ?.mapNotNull { value ->
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
            }
            ?.firstOrNull()
    }

    @Transactional(readOnly = true)
    fun findActiveAuthenticationMethods(accountId: Long): List<String> =
        findAccountProfile(accountId)
            ?.activeAuthenticationMethods
            ?: emptyList()

    private fun toProfile(account: Account): AccountProfile {
        val activeMethods: List<String> = account.authenticationMethods
            .filter { it.active }
            .mapNotNull { it.method }
            .distinct()
        return AccountProfile(account.id, account.personId, activeMethods)
    }
}

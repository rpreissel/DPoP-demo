package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountIdentification
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.account.internal.AuthenticationMethod
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_spi.EnrollmentRef
import java.time.Instant
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(private val accountRepository: AccountRepository) : AccountDirectory {

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

    /**
     * [allowsMultipleInstances] (docs/03-tool-architektur.md, ToolDescriptor - currently only
     * `device`): when true, an existing active entry for the same [method] is left untouched
     * instead of being deactivated - several physical devices can each hold their own active
     * `device` credential at once. [label] is a user-chosen display name, meaningful only for
     * multi-instance methods (null for singleton ones - the frontend labels those from the
     * method name itself).
     */
    @Transactional
    fun addAuthenticationMethod(
        accountId: Long,
        method: String,
        enrollmentRef: EnrollmentRef,
        enrolledUnderAcr: String?,
        details: Map<String, Any?>,
        allowsMultipleInstances: Boolean = false,
        label: String? = null
    ): AccountProfile {
        val account = getOrThrow(accountId)
        // Re-enrolling a SINGLETON method (e.g. a new phone number/email) REPLACES the old
        // credential rather than shadowing it - without this, two "active" entries for the same
        // method could coexist (nothing elsewhere ever deactivates a superseded one), and
        // canAccountReach/candidateTools/resolveAcr would inconsistently see both. Multi-instance
        // methods are exempt on purpose - that coexistence is the whole point there.
        if (!allowsMultipleInstances) {
            account.authenticationMethods.filter { it.method == method && it.active }.forEach { it.active = false }
        }
        val mergedDetails = details + mapOf("enrollmentRef" to mapOf("type" to enrollmentRef.type, "id" to enrollmentRef.id))
        account.addAuthenticationMethod(
            AuthenticationMethod(method, true, Instant.now(), enrolledUnderAcr, mergedDetails).apply {
                id = UUID.randomUUID().toString()
                this.label = label
            }
        )
        return toProfile(accountRepository.save(account))
    }

    /**
     * Called only for an account-initiated deactivation (AuthIntent.MANAGE_AUTH_METHODS) - the caller must
     * already have verified this won't drop the account below its channel's required floor.
     * Addressed by the entry's own [methodInstanceId], never by method name: several active
     * entries can share the same method name (multiple devices), so name alone can't tell them
     * apart, and deactivating by name would risk silently switching off ALL of them at once.
     */
    @Transactional
    fun deactivateAuthenticationMethod(accountId: Long, methodInstanceId: String): AccountProfile {
        val account = getOrThrow(accountId)
        account.authenticationMethods.filter { it.id == methodInstanceId && it.active }.forEach { it.active = false }
        return toProfile(accountRepository.save(account))
    }

    @Transactional(readOnly = true)
    fun findAccount(accountId: Long): AccountProfile? =
        accountRepository.findByIdOrNull(accountId)?.let { toProfile(it) }

    /**
     * Deletes only the account row itself - this module must never depend on a method module
     * (see [ModuleMetadata]), so the caller is responsible for first cleaning up whatever
     * credentials the account's own `authenticationMethods` referenced (docs/05-api.md, Account
     * löschen), and for the account-adjacent orchestrator state (DeviceAccountLink, AuthContext).
     */
    @Transactional
    fun deleteAccount(accountId: Long) {
        accountRepository.deleteById(accountId)
    }

    /**
     * Every enrollmentRef this account's `authenticationMethods` entries ever pointed at - active
     * AND already-superseded/deactivated alike, so a caller cleaning up cross-module credential
     * rows on account deletion doesn't leave a replaced phone number's/device's row behind.
     */
    @Transactional(readOnly = true)
    fun allEnrollmentRefs(accountId: Long): List<EnrollmentRef> =
        findAccount(accountId)?.authenticationMethods?.mapNotNull { extractEnrollmentRef(it) } ?: emptyList()

    @Transactional(readOnly = true)
    fun findAccountByEmail(email: String): AccountProfile? =
        accountRepository.findByEmail(email)?.let { toProfile(it) }

    @Transactional(readOnly = true)
    fun existsByEmail(email: String): Boolean = accountRepository.existsByEmail(email)

    /**
     * Called by `EnrollEmailToolHandler` on the one procedure that can establish a confirmed
     * address. `auth_email` is the single method module allowed to reach into this module for
     * exactly this reason - the confirmed email is the account's identifier, not a swappable
     * credential (see that module's `ModuleMetadata`, enforced by Spring Modulith).
     */
    @Transactional
    fun confirmEmail(accountId: Long, email: String): AccountProfile {
        val account = getOrThrow(accountId)
        account.email = email
        account.emailConfirmedAt = Instant.now()
        return toProfile(accountRepository.save(account))
    }

    @Transactional(readOnly = true)
    fun findActiveMethod(accountId: Long, method: String): AuthMethodView? =
        findAccount(accountId)?.authenticationMethods?.firstOrNull { it.active && it.method == method }

    /** All active instances of [method] - plural sibling of [findActiveMethod] for multi-instance methods (e.g. several active `device` entries, one per physical device). */
    @Transactional(readOnly = true)
    fun findActiveMethods(accountId: Long, method: String): List<AuthMethodView> =
        findAccount(accountId)?.authenticationMethods?.filter { it.active && it.method == method } ?: emptyList()

    // AccountDirectory (tool_api) -------------------------------------------------------------

    override fun resolveAccountByEmail(email: String): Long? = findAccountByEmail(email)?.accountId

    override fun activeEnrollment(accountId: Long, method: String): EnrollmentRef? =
        findActiveMethod(accountId, method)?.let { extractEnrollmentRef(it) }

    override fun activeInstanceEnrollment(accountId: Long, method: String, matchesCaller: (Map<String, Any?>?) -> Boolean): EnrollmentRef? =
        findActiveMethods(accountId, method)
            .firstOrNull { matchesCaller(it.details) }
            ?.let { extractEnrollmentRef(it) }

    /** The inverse of [addAuthenticationMethod]'s `mergedDetails` write - reads the same shape back out. */
    private fun extractEnrollmentRef(method: AuthMethodView): EnrollmentRef? {
        val raw = method.details?.get("enrollmentRef") as? Map<*, *> ?: return null 
        val type = raw["type"] as? String ?: return null
        val id = raw["id"] as? String ?: return null
        return EnrollmentRef(type, id)
    }

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
            AuthMethodView(it.id, it.method.orEmpty(), it.active, it.createdAt, it.enrolledUnderAcr, it.details, it.label)
        },
        email = account.email,
        emailConfirmedAt = account.emailConfirmedAt
    )
}

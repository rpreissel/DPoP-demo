package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountAuthMethod
import com.example.dpop.account.internal.AccountAuthMethodRepository
import com.example.dpop.account.internal.AccountIdentification
import com.example.dpop.account.internal.AccountIdentificationRepository
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.AttributeAuthority
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.allowsAnchorReplacement
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.validateValue
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Fired after a change to an account's current state (creation, anchors, methods) - the
 * account-sync mechanism (`orchestrator.kc.KeycloakAccountSyncListener`, only wired up under the
 * `keycloak` Spring profile) listens for this to keep a mirrored Keycloak user in sync, but the
 * event itself is profile-agnostic. [accountId] alone (not a snapshot) - a listener re-reads the
 * current state via [AccountService.findAccount]. Appends to the audit logs do not fire it.
 */
data class AccountChanged(val accountId: Long)

/** Fired once an account row is actually gone - unlike [AccountChanged], nothing left to re-read, so this carries everything a listener gets. */
data class AccountDeleted(val accountId: Long)

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val accountAttributeRepository: AccountAttributeRepository,
    private val accountAnchorRepository: AccountAnchorRepository,
    private val accountAuthMethodRepository: AccountAuthMethodRepository,
    private val accountIdentificationRepository: AccountIdentificationRepository,
    private val eventPublisher: ApplicationEventPublisher
) : AccountDirectory {

    private val log = LoggerFactory.getLogger(AccountService::class.java)

    /** Single-claim convenience wrapper around [recordClaims]. */
    @Transactional
    fun recordClaim(accountId: Long, claim: Claim) = recordClaims(accountId, listOf(claim))

    /**
     * The claims-log write path (docs/ideen/claims-modell-und-vertrauensanker.md) for every
     * [claims] a single completed tool run asserted, applied together. Never overwrites a prior
     * claim; the log is provenance. At most one claim per [AttributeType] - the same contract
     * `assertClaimsCovered` checks upstream, re-checked here so this method is safe on its own.
     * A locally owned attribute (`AttributeAuthority.LOCAL_ANCHOR`: `PERSON_ID`, `EMAIL`) is
     * additionally consolidated into its [AccountAnchor]; every other attribute keeps its
     * authority where [AttributeType.authority] says it lives (`ext_stammdaten`, reachable via
     * the PERSON_ID anchor, or a method module's own enrollment row) and is only logged here.
     */
    @Transactional
    fun recordClaims(accountId: Long, claims: List<Claim>) {
        val seen = mutableSetOf<AttributeType>()
        claims.forEach { claim ->
            claim.validateValue()
            check(seen.add(claim.attributeType)) {
                "recordClaims($accountId): more than one claim for ${claim.attributeType.wireName}"
            }
        }
        claims.forEach { claim ->
            val establishedAt = Instant.now()
            accountAttributeRepository.save(
                AccountAttribute(
                    accountId = accountId,
                    attributeType = claim.attributeType,
                    value = claim.value,
                    claimSource = claim.source.value,
                    establishedLoa = claim.establishedLoa?.value,
                    establishedAt = establishedAt
                )
            )
            if (claim.attributeType.authority == AttributeAuthority.LOCAL_ANCHOR) {
                lockForUpdate(accountId)
                recordAnchor(accountId, claim.attributeType, claim.value, establishedAt)
                eventPublisher.publishEvent(AccountChanged(accountId))
            }
        }
    }

    /**
     * Materializes an anchor row. Idempotent when this account already holds the value. A value
     * held by ANOTHER account is rejected outright ([IdentityConflictException]), never
     * re-assigned and never silently skipped: ADR-11 makes a cross-account conflict an upstream
     * rejection, and throwing rolls the whole claim back, log entry included. A new value for
     * THIS account's own anchor re-binds it only if [AttributeType.allowsAnchorReplacement] says
     * so (`EMAIL`); for `PERSON_ID` (immutable after first binding) it is rejected the same way.
     *
     * A rebind UPDATES the row in place rather than deleting and re-inserting: Hibernate flushes
     * insertions before deletions, so a delete-then-insert pair would briefly hold both rows and
     * trip `ux_account_anchor_account_type`.
     */
    private fun recordAnchor(accountId: Long, type: AttributeType, value: String, establishedAt: Instant) {
        val normalized = type.normalizeAnchorValue(value)
        accountAnchorRepository.findByAttributeTypeAndValue(type, normalized)?.let { held ->
            if (held.accountId == accountId) return
            log.warn(
                "Anchor conflict: {} anchor already held by account {}, rejected for account {}",
                type.wireName, held.accountId, accountId
            )
            throw IdentityConflictException("Dieser ${type.wireName}-Wert gehoert bereits zu einem anderen Konto")
        }
        val existing = accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)
        if (existing != null) {
            if (!type.allowsAnchorReplacement) {
                log.warn(
                    "Anchor conflict: {} for account {} is immutable, already bound to {}, rejected new value",
                    type.wireName, accountId, existing.value
                )
                throw IdentityConflictException("Dieser ${type.wireName}-Wert kann fuer dieses Konto nicht mehr geaendert werden")
            }
            existing.value = normalized
            existing.establishedAt = establishedAt
            accountAnchorRepository.save(existing)
            return
        }
        accountAnchorRepository.save(
            AccountAnchor(
                accountId = accountId,
                attributeType = type,
                value = normalized,
                establishedAt = establishedAt
            )
        )
    }

    /**
     * Creates an account without a person binding. Identification binds it via [recordClaims],
     * in the SAME caller transaction, so a failed claim also rolls back the new account.
     * Enrollment-first registration may intentionally leave it unbound until later identification.
     */
    @Transactional
    fun createUnidentifiedAccount(): AccountProfile {
        val account = accountRepository.save(Account(createdAt = Instant.now()))
        val accountId = requireNotNull(account.id) { "Account has no id" }
        eventPublisher.publishEvent(AccountChanged(accountId))
        return AccountProfile(accountId = accountId, personId = null, authenticationMethods = emptyList())
    }

    /** Appends the audit record of one identification run - see [AccountIdentification]. */
    @Transactional
    fun addIdentification(accountId: Long, method: String, loa: String?, details: Map<String, Any?>?) {
        accountIdentificationRepository.save(
            AccountIdentification(
                accountId = accountId,
                method = method,
                achievedLoa = loa,
                identifiedAt = Instant.now(),
                details = details
            )
        )
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
        lockForUpdate(accountId)
        val now = Instant.now()
        val active = accountAuthMethodRepository.findByAccountIdAndMethodAndActiveTrueOrderByCreatedAt(accountId, method)
        if (!allowsMultipleInstances) {
            // Re-enrolling a SINGLETON method (e.g. a new phone number) REPLACES the old credential
            // rather than shadowing it - two active entries for the same method would be seen
            // inconsistently by canAccountReach/authCandidates/resolveAcr.
            active.forEach { it.deactivate(now) }
        } else if (active.any { it.enrollmentRef == enrollmentRef }) {
            // The SAME physical credential re-reported for the SAME account can only be a re-run of
            // an already-completed enrollment (a device is bound to one account at a time,
            // docs/09-dpop.md) - a second row would match every future lookup alike.
            return getProfileOrThrow(accountId)
        }
        accountAuthMethodRepository.save(
            AccountAuthMethod(
                accountId = accountId,
                method = method,
                enrollmentType = enrollmentRef.type,
                enrollmentId = enrollmentRef.id,
                enrolledUnderAcr = enrolledUnderAcr,
                label = label,
                details = details
            ).also { it.createdAt = now }
        )
        eventPublisher.publishEvent(AccountChanged(accountId))
        return getProfileOrThrow(accountId)
    }

    /**
     * Called only for an account-initiated deactivation (AuthIntent.MANAGE_AUTH_METHODS) - the caller must
     * already have verified this won't drop the account below its channel's required floor.
     * Addressed by the entry's own [methodInstanceId], never by method name: several active
     * entries can share the same method name (multiple devices).
     */
    @Transactional
    fun deactivateAuthenticationMethod(accountId: Long, methodInstanceId: String): AccountProfile {
        lockForUpdate(accountId)
        findMethodInstance(accountId, methodInstanceId)?.takeIf { it.active }?.deactivate(Instant.now())
        eventPublisher.publishEvent(AccountChanged(accountId))
        return getProfileOrThrow(accountId)
    }

    @Transactional(readOnly = true)
    fun findAccount(accountId: Long): AccountProfile? =
        accountRepository.findByIdOrNull(accountId)?.let { toProfile(it) }

    /** Every account id that currently exists - the full-reconciliation counterpart of the per-event [AccountChanged]/[AccountDeleted] (see `KeycloakAccountSyncService`'s explicit "Sync with Keycloak" action, which also needs to find KEYCLOAK-side orphans nothing here still references). */
    @Transactional(readOnly = true)
    fun allAccountIds(): List<Long> = accountRepository.findAllIds()

    /**
     * Deletes the account row; its anchors, methods and both audit logs cascade with it. This
     * module must never depend on a method module (see [ModuleMetadata]), so the caller is
     * responsible for first cleaning up the credentials [allEnrollmentRefs] points at
     * (docs/05-api.md, Account löschen) and for the account-adjacent orchestrator state.
     */
    @Transactional
    fun deleteAccount(accountId: Long) {
        accountRepository.deleteById(accountId)
        eventPublisher.publishEvent(AccountDeleted(accountId))
    }

    /**
     * Every enrollmentRef this account's methods ever pointed at - active AND deactivated alike,
     * so a caller cleaning up cross-module credential rows on account deletion doesn't leave a
     * replaced phone number's/device's row behind.
     */
    @Transactional(readOnly = true)
    fun allEnrollmentRefs(accountId: Long): List<EnrollmentRef> =
        accountAuthMethodRepository.findByAccountIdOrderByCreatedAt(accountId).map { it.enrollmentRef }

    /** The [EnrollmentRef] for ONE method instance - for a caller revoking a single credential (`AccountDeletionService.revokeMethod`). */
    @Transactional(readOnly = true)
    fun enrollmentRefFor(accountId: Long, methodInstanceId: String): EnrollmentRef? =
        findMethodInstance(accountId, methodInstanceId)?.enrollmentRef

    @Transactional(readOnly = true)
    fun findActiveMethod(accountId: Long, method: String): AuthMethodView? =
        findActiveMethods(accountId, method).firstOrNull()

    /** All active instances of [method] - plural sibling of [findActiveMethod] for multi-instance methods (e.g. several active `device` entries, one per physical device). */
    @Transactional(readOnly = true)
    fun findActiveMethods(accountId: Long, method: String): List<AuthMethodView> =
        accountAuthMethodRepository.findByAccountIdAndMethodAndActiveTrueOrderByCreatedAt(accountId, method).map { it.toView() }

    // AccountDirectory (tool_api) -------------------------------------------------------------

    override fun resolveByAnchor(type: AttributeType, value: String): Long? =
        accountAnchorRepository.findByAttributeTypeAndValue(type, type.normalizeAnchorValue(value))?.accountId

    override fun anchorValue(accountId: Long, type: AttributeType): String? {
        check(type.authority == AttributeAuthority.LOCAL_ANCHOR) { "$type is not a local account anchor, it is owned by ${type.authority}" }
        return accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.value
    }

    override fun activeEnrollment(accountId: Long, method: String): EnrollmentRef? =
        findActiveMethod(accountId, method)?.enrollmentRef

    override fun activeInstanceEnrollment(accountId: Long, method: String, matchesCaller: (Map<String, Any?>?) -> Boolean): EnrollmentRef? =
        findActiveMethods(accountId, method).firstOrNull { matchesCaller(it.details) }?.enrollmentRef

    private fun lockForUpdate(accountId: Long): Account =
        accountRepository.findForUpdate(accountId) ?: throw IllegalArgumentException("Account not found: $accountId")

    private fun findMethodInstance(accountId: Long, methodInstanceId: String): AccountAuthMethod? {
        val id = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return null
        return accountAuthMethodRepository.findByIdAndAccountId(id, accountId)
    }

    private fun getProfileOrThrow(accountId: Long): AccountProfile =
        findAccount(accountId) ?: throw IllegalArgumentException("Account not found: $accountId")

    private fun toProfile(account: Account): AccountProfile {
        val accountId = requireNotNull(account.id) { "Account has no id" }
        val anchors = accountAnchorRepository.findByAccountId(accountId).associateBy { it.attributeType }
        val emailAnchor = anchors[AttributeType.EMAIL]
        return AccountProfile(
            accountId = accountId,
            personId = anchors[AttributeType.PERSON_ID]?.value?.toLong(),
            authenticationMethods = accountAuthMethodRepository.findByAccountIdOrderByCreatedAt(accountId).map { it.toView() },
            email = emailAnchor?.value,
            emailConfirmedAt = emailAnchor?.establishedAt
        )
    }

    private fun AccountAuthMethod.toView() = AuthMethodView(
        id = id.toString(),
        method = method.orEmpty(),
        active = active,
        createdAt = createdAt,
        enrolledUnderAcr = enrolledUnderAcr,
        details = details,
        enrollmentRef = enrollmentRef,
        label = label
    )
}

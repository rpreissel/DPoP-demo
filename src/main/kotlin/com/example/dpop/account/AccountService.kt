package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountClaim
import com.example.dpop.account.internal.AccountClaimRepository
import com.example.dpop.account.internal.AccountAuthMethod
import com.example.dpop.account.internal.AccountAuthMethodRepository
import com.example.dpop.account.internal.AccountIdentification
import com.example.dpop.account.internal.AccountIdentificationRepository
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.account.internal.AccountRetraction
import com.example.dpop.account.internal.AccountRetractionRepository
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.AttributeAuthority
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_api.rule
import com.example.dpop.tool_spi.AcrLevel
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
    private val accountClaimRepository: AccountClaimRepository,
    private val accountAnchorRepository: AccountAnchorRepository,
    private val accountAuthMethodRepository: AccountAuthMethodRepository,
    private val accountIdentificationRepository: AccountIdentificationRepository,
    private val accountRetractionRepository: AccountRetractionRepository,
    private val eventPublisher: ApplicationEventPublisher
) : AccountDirectory {

    private val log = LoggerFactory.getLogger(AccountService::class.java)

    /** Single-claim convenience wrapper around [recordClaims]. */
    @Transactional
    fun recordClaim(accountId: Long, claim: Claim, provenAcr: AcrLevel) =
        recordClaims(accountId, listOf(claim), provenAcr)

    /**
     * Withdraws what one method instance asserted, as its own retraction row per distinct value -
     * the claim log itself is never touched (ADR-12, docs/12-entscheidungen.md).
     *
     * Retracts only claims whose [AttributeRule.authority] is [AttributeAuthority.METHOD_MODULE]:
     * an anchor (`EMAIL`) or master-data attribute (`NAME`) is an identity fact OF THE ACCOUNT,
     * not an artifact of the method, and outlives the credential - otherwise removing the email
     * method would silently strip the account's identity anchor and with it password login.
     *
     * @return how many retraction rows were written - 0 is the ordinary case for a method that
     *   asserts nothing (device, password) or only account-owned facts.
     */
    @Transactional
    fun retractClaimsOf(
        accountId: Long,
        methodInstanceId: String,
        trustAnchor: RetractionAnchor,
        reason: String? = null
    ): Int {
        val instanceId = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return 0
        val now = Instant.now()
        val retractable = accountClaimRepository.findByAuthMethodId(instanceId)
            .filter { it.accountId == accountId }
            .filter { it.attributeType?.rule?.authority == AttributeAuthority.METHOD_MODULE }
            .mapNotNull { claim -> claim.attributeType?.let { type -> type to claim.normalizedValue } }
            .distinct()
        retractable.forEach { (type, value) ->
            accountRetractionRepository.save(
                AccountRetraction(
                    accountId = accountId,
                    attributeType = type,
                    normalizedValue = value,
                    trustAnchor = trustAnchor,
                    reason = reason,
                    retractedAt = now
                )
            )
        }
        return retractable.size
    }

    /**
     * The claims-log write path (docs/ideen/claims-modell-und-vertrauensanker.md) for every
     * [claims] a single completed tool run asserted, applied together. Never overwrites a prior
     * claim; the log is provenance. At most one claim per [AttributeType] - the same contract
     * `assertClaimsCovered` checks upstream, re-checked here so this method is safe on its own.
     * A locally owned attribute (`AttributeAuthority.LOCAL_ANCHOR`: `PERSON_ID`, `EMAIL`) is
     * additionally consolidated into its [AccountAnchor]; every other attribute is only logged
     * here, its authority living elsewhere (`ext_stammdaten`, or a method module's own row).
     *
     * [provenAcr] is what the session ACTUALLY established when this run completed - the same
     * capped figure `AccountAuthMethod.enrolledUnderAcr` records, never a tool's own declared
     * ceiling. It is the price an anchor write is paid with ([AnchorRule.acrFloor]); the log
     * itself is never gated - a claim below the floor is still provenance, it just does not move
     * the anchor.
     */
    @Transactional
    fun recordClaims(
        accountId: Long,
        claims: List<Claim>,
        provenAcr: AcrLevel,
        authMethodId: UUID? = null
    ) {
        val seen = mutableSetOf<AttributeType>()
        claims.forEach { claim ->
            claim.validateValue()
            check(seen.add(claim.attributeType)) {
                "recordClaims($accountId): more than one claim for ${claim.attributeType.wireName}"
            }
        }
        claims.forEach { claim ->
            val establishedAt = Instant.now()
            accountClaimRepository.save(
                AccountClaim(
                    accountId = accountId,
                    attributeType = claim.attributeType,
                    value = claim.value,
                    claimSource = claim.source.value,
                    establishedLoa = claim.establishedLoa?.value,
                    authMethodId = authMethodId,
                    establishedAt = establishedAt
                )
            )
            if (claim.attributeType.rule.authority == AttributeAuthority.LOCAL_ANCHOR) {
                lockForUpdate(accountId)
                recordAnchor(accountId, claim.attributeType, claim.value, establishedAt, provenAcr)
                eventPublisher.publishEvent(AccountChanged(accountId))
            }
        }
    }

    /**
     * Materializes an anchor row. Idempotent when this account already holds the value. A value
     * held by ANOTHER account is rejected outright ([IdentityConflictException]), never
     * re-assigned: ADR-11 makes a cross-account conflict an upstream rejection, and throwing rolls
     * the whole claim back, log entry included. A new value for THIS account's own anchor
     * re-binds it only if [AnchorRule.allowsReplacement] says so (`EMAIL`); `PERSON_ID` (immutable
     * after first binding) is rejected the same way. Only ever called from the `LOCAL_ANCHOR`
     * branch of [recordClaims] - [type] is guaranteed to have an [AnchorRule].
     *
     * A rebind UPDATES the row in place rather than deleting and re-inserting: Hibernate flushes
     * insertions before deletions, so a delete-then-insert pair would briefly hold both rows and
     * trip `ux_anchor_account_type`.
     *
     * Both writes are priced separately by [AnchorRule.acrFloor] and refused below it -
     * establishing binds a value to an account, replacing re-points an account that other people's
     * lookups already resolve through, which is the write worth protecting.
     */
    private fun recordAnchor(
        accountId: Long,
        type: AttributeType,
        value: String,
        establishedAt: Instant,
        provenAcr: AcrLevel
    ) {
        val anchor = checkNotNull(type.rule.anchor) { "$type is not a local anchor attribute" }
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
            if (!anchor.allowsReplacement) {
                log.warn(
                    "Anchor conflict: {} for account {} is immutable, already bound to {}, rejected new value",
                    type.wireName, accountId, existing.value
                )
                throw IdentityConflictException("Dieser ${type.wireName}-Wert kann fuer dieses Konto nicht mehr geaendert werden")
            }
            requireAnchorAcr(type, provenAcr, floor = anchor.acrFloor.replace, write = "ersetzt")
            existing.value = normalized
            existing.establishedLoa = provenAcr.value
            existing.establishedAt = establishedAt
            accountAnchorRepository.save(existing)
            return
        }
        requireAnchorAcr(type, provenAcr, floor = anchor.acrFloor.establish, write = "gesetzt")
        accountAnchorRepository.save(
            AccountAnchor(
                accountId = accountId,
                attributeType = type,
                value = normalized,
                establishedLoa = provenAcr.value,
                establishedAt = establishedAt
            )
        )
    }

    /**
     * Refuses an anchor write the session has not paid for. Rejecting rather than silently logging
     * the claim without its anchor: a caller that believed it bound an identity must not proceed on
     * a false premise (ADR-11's line - reject, never quietly skip).
     */
    private fun requireAnchorAcr(type: AttributeType, provenAcr: AcrLevel, floor: AcrLevel, write: String) {
        if (AcrLevel.rank(provenAcr) >= AcrLevel.rank(floor)) return
        log.warn(
            "Anchor floor: {} may only be {} at {} or above, session proved {}",
            type.wireName, write, floor.value, provenAcr.value
        )
        throw IdentityConflictException(
            "Dieser ${type.wireName}-Wert kann erst ab ${floor.value} $write werden, nachgewiesen ist ${provenAcr.value}"
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
        label: String? = null,
        /**
         * The instance id to use, when the caller already had to know it before this call - the
         * enrollment path generates it up front so the claims it records can point at the
         * instance that established them (ADR-12). Defaults to a fresh one.
         */
        instanceId: UUID = UUID.randomUUID()
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
            ).also { it.id = instanceId; it.createdAt = now }
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
        check(type.rule.authority == AttributeAuthority.LOCAL_ANCHOR) { "$type is not a local account anchor, it is owned by ${type.rule.authority}" }
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

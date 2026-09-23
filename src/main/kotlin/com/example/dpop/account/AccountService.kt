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
import com.example.dpop.account.internal.strongestEstablishedValues
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.AttributeAuthority
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_api.anchorRule
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_api.isLocalAnchor
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.trustLevel
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.validateValue
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Fired after a change to an account's current state (creation, anchors, methods) - the
 * account-sync mechanism (`orchestrator.kc.KeycloakAccountSyncListener`, only wired up under the
 * `keycloak` Spring profile) listens for this to keep a mirrored Keycloak user in sync, but the
 * event itself is profile-agnostic. [accountId] alone (not a snapshot) - a listener re-reads the
 * current state via [AccountService.findAccount]. Appends to the audit logs do not fire it.
 *
 * At most ONE per account and transaction, however many changes that transaction makes (see
 * [AccountService.announceChanged]): the event says "re-read this account", not "this one thing
 * changed", so a second one for the same transaction would only start a second identical sync.
 */
data class AccountChanged(val accountId: Long)

/** Transaction resource holding the account ids [AccountService.announceChanged] already published for. */
private const val ANNOUNCED_KEY = "account.AccountChanged.announced"

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
     * Retracts only claims whose [AttributeType.authority] is [AttributeAuthority.MethodModule]:
     * an anchor (`EMAIL`) or master-data attribute (`NAME`) is an identity fact OF THE ACCOUNT,
     * not an artifact of the method, and outlives the credential - otherwise removing the email
     * method would silently strip the account's identity anchor and with it password login.
     *
     * @return how many retraction rows were written - 0 is the ordinary case for a method that
     *   asserts nothing (device, password) or only account-owned facts.
     */
    /**
     * Which attribute types this one method instance asserted and would take with it when
     * revoked - i.e. exactly what [retractClaimsOf] would retract, asked without retracting.
     *
     * Exists so a caller can work out the consequences of a removal BEFORE writing anything
     * (`JourneyActionExecutor.removeMethod` projects the account's state after the removal to
     * check it against the channel's floor). Deliberately the same query and the same
     * MethodModule filter as the retraction itself rather than a second, independently derived
     * answer - a projection that disagreed with the write it predicts would be worse than none.
     */
    @Transactional(readOnly = true)
    fun claimedTypesOf(accountId: Long, methodInstanceId: String): Set<AttributeType> {
        val instanceId = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return emptySet()
        return accountClaimRepository.findByAuthMethodId(instanceId)
            .filter { it.accountId == accountId }
            .filter { it.attributeType?.authority == AttributeAuthority.MethodModule }
            .mapNotNull { it.attributeType }
            .toSet()
    }

    /**
     * Withdraws ONE attribute of this account outright - the act [retractClaimsOf] deliberately
     * cannot perform: that one only ever drops what a revoked credential itself asserted, and
     * an anchor like EMAIL is an account-owned fact no method may take with it.
     *
     * Until this existed, a confirmed address could never be lost: `confirm-email` writes its
     * claim as an ATTESTATION, i.e. with no `authMethodId` at all, so no method revocation could
     * ever reach it. It is therefore the retraction itself that has to be a first-class act -
     * and the caller's job to deal with what depended on the attribute
     * (`JourneyActionExecutor.performRetractAttribute`).
     *
     * Follows ADR-12 for anchors: the retraction row makes the claim log stop establishing the
     * value, and the anchor row is deleted outright, so nothing resolves an account by it
     * afterwards.
     *
     * @return true if something was actually established and is now withdrawn.
     */
    @Transactional
    fun retractAttribute(
        accountId: Long,
        attributeType: AttributeType,
        trustAnchor: RetractionAnchor,
        reason: String? = null
    ): Boolean {
        // Only what still counts: a value already withdrawn needs no second retraction row, and
        // findEstablished is the same "assertions minus retractions" view every reader uses.
        val established = accountClaimRepository.findEstablished(accountId)
            .filter { it.attributeType == attributeType }
            .map { it.normalizedValue }
            .distinct()
        if (established.isEmpty()) return false

        val now = Instant.now()
        established.forEach { value ->
            accountRetractionRepository.save(
                AccountRetraction(
                    accountId = accountId,
                    attributeType = attributeType,
                    normalizedValue = value,
                    trustAnchor = trustAnchor,
                    reason = reason,
                    retractedAt = now
                )
            )
        }
        if (attributeType.isLocalAnchor) {
            accountAnchorRepository.findByAccountIdAndAttributeType(accountId, attributeType)
                ?.let { accountAnchorRepository.delete(it) }
        }
        return true
    }

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
            .filter { it.attributeType?.authority == AttributeAuthority.MethodModule }
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
     * A locally owned attribute (`AttributeAuthority.Local`: `PERSON_ID`,
     * `EID_RESTRICTED_ID`, `EMAIL`) is additionally consolidated into its [AccountAnchor]; every
     * other attribute is only logged here, its authority living elsewhere (`ext_stammdaten`, or a
     * method module's own row).
     *
     * The log is a change log, not a run log: a claim whose (type, value, source, method) is
     * already established leaves no new row - WHEN identity was re-proven is
     * `AccountIdentification`'s story, and re-attesting the same card eight times must not cost
     * eight log rows. The method instance is part of the key so a fresh enrollment of a known
     * value still logs: revoking the OLD instance retracts only what THAT instance asserted.
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
        val logged = accountClaimRepository.findEstablished(accountId)
            .map { claim ->
                EstablishedClaimKey(
                    checkNotNull(claim.attributeType),
                    checkNotNull(claim.normalizedValue),
                    checkNotNull(claim.claimSource),
                    claim.authMethodId
                )
            }
            .toMutableSet()
        claims.forEach { claim ->
            val establishedAt = Instant.now()
            if (logged.add(
                    EstablishedClaimKey(
                        claim.attributeType,
                        checkNotNull(AccountClaim.normalize(claim.value)),
                        claim.source.value,
                        authMethodId
                    )
                )
            ) {
                accountClaimRepository.save(
                    AccountClaim(
                        accountId = accountId,
                        attributeType = claim.attributeType,
                        value = claim.value,
                        claimSource = claim.source.value,
                        establishedAcr = claim.establishedAcr?.value,
                        authMethodId = authMethodId,
                        establishedAt = establishedAt
                    )
                )
            }
            if (claim.attributeType.isLocalAnchor) {
                lockForUpdate(accountId)
                recordAnchor(accountId, claim.attributeType, claim.value, establishedAt, provenAcr)
                announceChanged(accountId)
            }
        }
    }

    /**
     * Materializes an anchor row. Idempotent when this account already holds the value. A value
     * held by ANOTHER account is rejected outright ([IdentityConflictException]), never
     * re-assigned: ADR-11 makes a cross-account conflict an upstream rejection, and throwing rolls
     * the whole claim back, log entry included. A new value for THIS account's own anchor
     * re-binds it only if [AnchorRule.allowsReplacement] says so (`EMAIL`); `PERSON_ID` (immutable
     * after first binding) is rejected the same way. Only ever called from the `AttributeAuthority.Local`
     * branch of [recordClaims] - [type] is guaranteed to have an [AnchorRule].
     *
     * A rebind UPDATES the row in place rather than deleting and re-inserting: Hibernate flushes
     * insertions before deletions, so a delete-then-insert pair would briefly hold both rows and
     * trip `ux_anchor_account_type`. The same rebind also RETRACTS the old value from the claim
     * log ([AccountRetraction], `ACCOUNT_MANAGEMENT`): the log has to agree with the anchor
     * (ADR-19) instead of keeping a replaced value established forever. Claim-log normalization
     * applies to the retracted value - the anchor's own differs for case-preserving types.
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
        val anchor = checkNotNull(type.anchorRule) { "$type is not a local anchor attribute" }
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
            // ADR-19: the replaced value verfaellt - a retraction makes the log agree with the
            // anchor instead of keeping the old value established forever. Claim-log
            // normalization applies (the anchor's own differs for case-preserving types).
            accountRetractionRepository.save(
                AccountRetraction(
                    accountId = accountId,
                    attributeType = type,
                    normalizedValue = AccountClaim.normalize(existing.value),
                    trustAnchor = RetractionAnchor.ACCOUNT_MANAGEMENT,
                    reason = "anker-ersetzt",
                    retractedAt = establishedAt
                )
            )
            existing.value = normalized
            existing.establishedAcr = provenAcr.value
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
                establishedAcr = provenAcr.value,
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
        val accountId = checkNotNull(account.id) { "Account has no id" }
        announceChanged(accountId)
        return AccountProfile(accountId = accountId, personId = null, authenticationMethods = emptyList())
    }

    /**
     * ADR-20 ("a provisional account is absorbed instead of the run being rejected"): moves
     * everything a **provisional** account
     * ([AccountProfile.isProvisional]) ever established onto [into] and deletes it. The one case
     * this exists for: an ident-first journey attested an identity, created a placeholder account
     * for it, and the correlation step that follows then resolves a DIFFERENT, existing account -
     * a conflict the journey created itself, with the user having done nothing wrong.
     *
     * Deliberately NOT a general "move identity data between accounts": [from] must be
     * provisional (re-checked here, so this is safe called on its own), which is exactly what
     * makes the move harmless - nothing durable ever hung off it, no credential's provenance is
     * torn from its account, and no second person's facts are merged in.
     *
     * The order is load-bearing, not an implementation detail: `account.anchor` is globally
     * unique per (type, value) (`ux_anchor_value`), so [from]'s anchors have to be GONE before
     * the very same values can be written on [into] - read, release, then write. The write itself
     * goes through the ordinary [recordClaim] path, one claim at a time in the order they were
     * originally established: every conflict check, ACR floor and retraction rule (ADR-12) then
     * applies to the absorbing account exactly as it did to the account that yielded, and an
     * anchor [into] already holds with the same value is the no-op [recordAnchor] already is.
     * Claim by claim rather than batched, because the log may legitimately hold several values of
     * the same attribute (two eID cards in one run) - replaying them in order reproduces the same
     * end state instead of tripping the one-claim-per-attribute contract.
     *
     * What stays behind on purpose: retracted claims (they were withdrawn, and
     * [AccountClaimRepository.findEstablished] never returns them) and the claim rows' original
     * timestamps - the absorbing account records WHEN it took them on, while WHEN identity was
     * proven stays in the [AccountIdentification] rows, which are replayed with their original
     * `identifiedAt` and details.
     */
    @Transactional
    fun absorbProvisionalAccount(from: Long, into: Long) {
        check(from != into) { "absorbProvisionalAccount($from): an account cannot absorb itself" }
        val source = findAccount(from) ?: error("Account not found: $from")
        if (!source.isProvisional) {
            throw IdentityConflictException("Konto $from ist kein vorlaeufiges Konto und kann nicht aufgehen")
        }
        checkNotNull(findAccount(into)) { "Account not found: $into" }

        val anchors = accountAnchorRepository.findByAccountId(from)
        // The price each anchor write was originally paid with (AnchorRule.acrFloor) - re-used
        // here rather than the CURRENT session's level, so absorbing neither under- nor overpays
        // for what was already established.
        val anchorAcr = anchors.mapNotNull { anchor ->
            anchor.attributeType?.let { type -> type to (anchor.establishedAcr?.let(AcrLevel::of) ?: AcrLevel.NONE) }
        }.toMap()
        val claims = accountClaimRepository.findEstablished(from).sortedBy { it.establishedAt }
        val identifications = accountIdentificationRepository.findByAccountIdOrderByIdentifiedAt(from)

        // Release the unique anchor values BEFORE the same values are written on `into`, and
        // flush it: Hibernate orders insertions before deletions within one flush, so without
        // this the re-write would trip `ux_anchor_value` against the account it is taking over
        // from (the same ordering trap `recordAnchor`'s in-place rebind documents).
        accountAnchorRepository.deleteAll(anchors)
        accountAnchorRepository.flush()
        deleteAccount(from)

        claims.forEach { claim ->
            val type = checkNotNull(claim.attributeType) { "Claim without an attribute type on account $from" }
            recordClaim(
                into,
                Claim(
                    attributeType = type,
                    value = checkNotNull(claim.value) { "Claim without a value on account $from" },
                    source = ClaimSource(checkNotNull(claim.claimSource) { "Claim without a source on account $from" }),
                    establishedAcr = claim.establishedAcr?.let(AcrLevel::of)
                ),
                provenAcr = anchorAcr[type] ?: claim.establishedAcr?.let(AcrLevel::of) ?: AcrLevel.NONE
            )
        }
        identifications.forEach { identification ->
            accountIdentificationRepository.save(
                AccountIdentification(
                    accountId = into,
                    method = identification.method,
                    achievedAcr = identification.achievedAcr,
                    identifiedAt = identification.identifiedAt,
                    // The run's own audit details stay as they were - plus where it was recorded
                    // first, so the absorbed account id stays traceable after its row is gone.
                    details = identification.details.orEmpty() + mapOf("absorbedFromAccountId" to from)
                )
            )
        }
        log.info("Account {} absorbed provisional account {} ({} claims, {} identifications)", into, from, claims.size, identifications.size)
    }

    /** Appends the audit record of one identification run - see [AccountIdentification]. */
    @Transactional
    fun addIdentification(accountId: Long, method: String, loa: String?, details: Map<String, Any?>?) {
        accountIdentificationRepository.save(
            AccountIdentification(
                accountId = accountId,
                method = method,
                achievedAcr = loa,
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
        announceChanged(accountId)
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
        announceChanged(accountId)
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
        check(type.isLocalAnchor) { "$type is not a local account anchor, it is owned by ${type.authority}" }
        return accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.value
    }

    /**
     * The established (asserted, non-retracted) claim VALUES for [types], strongest assertion per
     * attribute - the value-reading counterpart of [AccountProfile.establishedClaims] for surfaces
     * that need what the account had attested about itself, not just its trust level: the ID-token
     * name of an Interessent without a register person (ADR-18) and the Keycloak user mirror both
     * read through this instead of reaching into the log themselves. Same selection as
     * `IdentityMatchingService`'s attested-identity view, via the shared
     * [com.example.dpop.account.internal.strongestEstablishedValues].
     */
    fun establishedClaimValues(accountId: Long, types: Set<AttributeType>): Map<AttributeType, String> =
        accountClaimRepository.findEstablished(accountId).strongestEstablishedValues(types)

    override fun activeEnrollment(accountId: Long, method: String): EnrollmentRef? =
        findActiveMethod(accountId, method)?.enrollmentRef

    override fun activeInstanceEnrollment(accountId: Long, method: String, livesOnCallerKey: (instanceDetails: Map<String, Any?>?) -> Boolean): EnrollmentRef? =
        findActiveMethods(accountId, method).firstOrNull { livesOnCallerKey(it.details) }?.enrollmentRef

    /**
     * Publishes [AccountChanged] for [accountId] once per transaction. A single business step
     * changes an account several times in one transaction - the demo seeder records anchors, claims
     * and two methods, a registration step an anchor and a claim - and every one of those used to
     * publish its own event. Each was delivered after the commit as its own async sync, all reading
     * the same final state, all at the same time: redundant work, and in Keycloak's case a race
     * (duplicate user, duplicate keypair). The first change registers the event; the listeners
     * read the state as of commit anyway, so the later changes are already included.
     *
     * Outside a transaction (no synchronization active) there is nothing to coalesce with.
     */
    private fun announceChanged(accountId: Long) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.publishEvent(AccountChanged(accountId))
            return
        }
        @Suppress("UNCHECKED_CAST")
        val announced = TransactionSynchronizationManager.getResource(ANNOUNCED_KEY) as MutableSet<Long>?
            ?: mutableSetOf<Long>().also { set ->
                TransactionSynchronizationManager.bindResource(ANNOUNCED_KEY, set)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(ANNOUNCED_KEY)
                    }
                })
            }
        if (announced.add(accountId)) eventPublisher.publishEvent(AccountChanged(accountId))
    }

    private fun lockForUpdate(accountId: Long): Account =
        accountRepository.findForUpdate(accountId) ?: error("Account not found: $accountId")

    private fun findMethodInstance(accountId: Long, methodInstanceId: String): AccountAuthMethod? {
        val id = runCatching { UUID.fromString(methodInstanceId) }.getOrNull() ?: return null
        return accountAuthMethodRepository.findByIdAndAccountId(id, accountId)
    }

    private fun getProfileOrThrow(accountId: Long): AccountProfile =
        findAccount(accountId) ?: error("Account not found: $accountId")

    private fun toProfile(account: Account): AccountProfile {
        val accountId = checkNotNull(account.id) { "Account has no id" }
        val anchors = accountAnchorRepository.findByAccountId(accountId).associateBy { it.attributeType }
        val emailAnchor = anchors[AttributeType.EMAIL]
        return AccountProfile(
            accountId = accountId,
            personId = anchors[AttributeType.PERSON_ID]?.value?.toLong(),
            authenticationMethods = accountAuthMethodRepository.findByAccountIdOrderByCreatedAt(accountId).map { it.toView() },
            email = emailAnchor?.value,
            emailConfirmedAt = emailAnchor?.establishedAt,
            establishedClaims = establishedClaims(accountId)
        )
    }

    /** Assertions minus retractions, highest [TrustLevel] per attribute - see [AccountProfile.establishedClaims]. */
    private fun establishedClaims(accountId: Long): Map<AttributeType, TrustLevel> =
        accountClaimRepository.findEstablished(accountId)
            .mapNotNull { claim ->
                val type = claim.attributeType ?: return@mapNotNull null
                val source = claim.claimSource?.let(::ClaimSource) ?: return@mapNotNull null
                type to source.trustLevel
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, levels) -> levels.maxBy { it.rank } }

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

/**
 * What makes a claim already logged: same attribute, same normalized value, same source, same
 * method instance - the key [AccountService.recordClaims] skips on, keeping the claim log a
 * change log instead of a run log.
 */
private data class EstablishedClaimKey(
    val type: AttributeType,
    val normalizedValue: String,
    val source: String,
    val authMethodId: UUID?
)

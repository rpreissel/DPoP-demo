package com.example.dpop.account

import com.example.dpop.tool_api.PersonChanged
import com.example.dpop.texts.Text
import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountClaim
import com.example.dpop.account.internal.AccountClaimRepository
import com.example.dpop.account.internal.AccountAuthMethod
import com.example.dpop.account.internal.AccountAuthMethodRepository
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.account.internal.AccountRetraction
import com.example.dpop.account.internal.AccountRetractionRepository
import com.example.dpop.account.internal.MethodDeactivationReason
import com.example.dpop.account.internal.ChangeLog
import com.example.dpop.account.internal.PersonLookupKey
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
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Fired once an account row is actually gone; the only account event (review 2026-09-26, A-1). An
 * `AccountChanged` per change existed while Keycloak kept a mirrored user in step - since Keycloak
 * reads accounts itself (ADR-38) nobody needs to hear about a change.
 */
data class AccountDeleted(val accountId: Long)

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val accountClaimRepository: AccountClaimRepository,
    private val accountAnchorRepository: AccountAnchorRepository,
    private val accountAuthMethodRepository: AccountAuthMethodRepository,
    private val accountRetractionRepository: AccountRetractionRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val changeLog: ChangeLog,
    private val personLookupKey: PersonLookupKey,
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
        // The holder may only give up what is theirs to give up (AnchorRule.retractableByHolder) -
        // checked here, not only by the caller, so no path to this method can widen it.
        check(trustAnchor != RetractionAnchor.ACCOUNT_HOLDER || attributeType.anchorRule?.retractableByHolder == true) {
            "$attributeType cannot be withdrawn by the account holder"
        }
        // ADR-14: whoever changes the current state loads the account row with a version bump,
        // so a concurrent confirm-email cannot interleave with this withdrawal.
        lockForUpdate(accountId)
        // Only what still counts: a value already withdrawn needs no second retraction row, and
        // findEstablished is the same "assertions minus retractions" view every reader uses.
        val established = accountClaimRepository.findEstablished(accountId)
            .filter { it.attributeType == attributeType }
            .map { it.normalizedValue }
            .distinct()
        if (established.isEmpty()) return false

        val now = Instant.now()
        established.forEach { value ->
            saveRetraction(
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

    /**
     * What a replaced singleton instance asserted and its replacement does not (review 2026-09,
     * M-13): the old phone number stops counting once a new one replaced it, exactly as it would
     * after `revokeMethod`. A value the replacement asserts itself stays - retractions work by value
     * (ADR-12), and the replacement's claims are recorded before this runs, so retracting it here
     * would cancel the new claim as well.
     */
    private fun retractReplacedClaims(accountId: Long, replaced: List<UUID>, replacement: UUID, now: Instant) {
        if (replaced.isEmpty()) return
        fun ownedBy(instance: UUID) = accountClaimRepository.findByAuthMethodId(instance)
            .filter { it.accountId == accountId && it.attributeType?.authority == AttributeAuthority.MethodModule }
            .mapNotNull { claim -> claim.attributeType?.let { it to claim.normalizedValue } }
            .toSet()
        val kept = ownedBy(replacement)
        replaced.flatMap { ownedBy(it) }.distinct().filterNot { it in kept }.forEach { (type, value) ->
            saveRetraction(
                AccountRetraction(
                    accountId = accountId,
                    attributeType = type,
                    normalizedValue = value,
                    trustAnchor = RetractionAnchor.ACCOUNT_MANAGEMENT,
                    reason = "replaced",
                    retractedAt = now
                )
            )
        }
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
            saveRetraction(
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
     * The claims-log write path (docs/archiv/claims-modell-und-vertrauensanker.md) for every
     * [claims] a single completed tool run asserted, applied together. Never overwrites a prior
     * claim; the log is provenance. At most one claim per [AttributeType] - the same contract
     * `assertClaimsCovered` checks upstream, re-checked here so this method is safe on its own.
     * A locally owned attribute (`AttributeAuthority.Local`: `PERSON_ID`,
     * `EID_RESTRICTED_ID`, `EMAIL`) is additionally consolidated into its [AccountAnchor]; every
     * other attribute is only logged here, its authority living elsewhere (`ext_personenverzeichnis`, or a
     * method module's own row).
     *
     * The log is a change log, not a run log: a claim whose (type, value, source, method) is
     * already established leaves no new row - WHEN identity was re-proven is
     * the change log's story (IDENTIFIED events, ADR-39), and re-attesting the same card eight times must not cost
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
                        checkNotNull(AccountClaim.normalize(claim.attributeType, claim.value)),
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
                        // What was actually proven, not what the tool can reach at most: an
                        // enrollment from a loa1 session establishes at loa1 even if the tool's
                        // own ceiling is loa2 (ADR-5; review 2026-09, Phase F - the log used to
                        // keep the uncapped tool value).
                        establishedAcr = (claim.establishedAcr?.let { AcrLevel.min(it, provenAcr) } ?: provenAcr).value,
                        authMethodId = authMethodId,
                        establishedAt = establishedAt
                    )
                )
            }
            if (claim.attributeType.isLocalAnchor) {
                lockForUpdate(accountId)
                recordAnchor(accountId, claim.attributeType, claim.value, establishedAt, provenAcr)
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
            throw IdentityConflictException(Text("Dieser {type}-Wert gehoert bereits zu einem anderen Konto", "type" to type.wireName))
        }
        val existing = accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)
        if (existing != null) {
            if (!anchor.allowsReplacement) {
                log.warn(
                    "Anchor conflict: {} for account {} is immutable, already bound to {}, rejected new value",
                    type.wireName, accountId, existing.value
                )
                throw IdentityConflictException(Text("Dieser {type}-Wert kann fuer dieses Konto nicht mehr geaendert werden", "type" to type.wireName))
            }
            requireAnchorAcr(type, provenAcr, floor = anchor.acrFloor.replace, replacing = true)
            // ADR-19: the replaced value verfaellt - a retraction makes the log agree with the
            // anchor instead of keeping the old value established forever. Claim-log
            // normalization applies (the anchor's own differs for case-preserving types).
            //
            // Not when old and new are the same value in the log's terms: a case-preserving anchor
            // (eID restricted_id) can be replaced by a value differing only in case, and the
            // retraction - stamped with the new claim's own time - would then also void the claim
            // just being set (review 2026-09, Phase F).
            val replacedLogValue = AccountClaim.normalize(type, checkNotNull(existing.value))
            if (replacedLogValue != AccountClaim.normalize(type, value)) saveRetraction(
                AccountRetraction(
                    accountId = accountId,
                    attributeType = type,
                    normalizedValue = replacedLogValue,
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
        requireAnchorAcr(type, provenAcr, floor = anchor.acrFloor.establish, replacing = false)
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
    private fun requireAnchorAcr(type: AttributeType, provenAcr: AcrLevel, floor: AcrLevel, replacing: Boolean) {
        if (AcrLevel.rank(provenAcr) >= AcrLevel.rank(floor)) return
        log.warn(
            "Anchor floor: {} may only be {} at {} or above, session proved {}",
            type.wireName, if (replacing) "replaced" else "set", floor.value, provenAcr.value
        )
        throw IdentityConflictException(
            // Two sentences, not one with the verb as a value: a verb is wording, every language inflects it itself.
            if (replacing) {
                Text("Dieser {type}-Wert kann erst ab {floor} ersetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
            } else {
                Text("Dieser {type}-Wert kann erst ab {floor} gesetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
            }
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
     * proven stays in the change log: its IDENTIFIED events are carried over with their original
     * time ([ChangeLog.carryIdentifications]).
     */
    @Transactional
    fun absorbProvisionalAccount(from: Long, into: Long) {
        check(from != into) { "absorbProvisionalAccount($from): an account cannot absorb itself" }
        val source = findAccount(from) ?: error("Account not found: $from")
        if (!source.isProvisional) {
            throw IdentityConflictException(
                Text("Dieses Konto ist bereits vollstaendig angelegt und kann nicht in ein anderes Konto uebernommen werden"),
                "account $from is not provisional, cannot be absorbed into $into"
            )
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

        // Release the unique anchor values BEFORE the same values are written on `into`, and
        // flush it: Hibernate orders insertions before deletions within one flush, so without
        // this the re-write would trip `ux_anchor_value` against the account it is taking over
        // from (the same ordering trap `recordAnchor`'s in-place rebind documents).
        accountAnchorRepository.deleteAll(anchors)
        accountAnchorRepository.flush()
        changeLog.accountAbsorbed(into, from)
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
        log.info("Account {} absorbed provisional account {} ({} claims)", into, from, claims.size)
    }

    /** Every withdrawal goes through here, so none escapes the change log (ADR-39) - the value stays in the retraction row, which goes with the account. */
    private fun saveRetraction(retraction: AccountRetraction): AccountRetraction {
        changeLog.attributeRetracted(
            checkNotNull(retraction.accountId), retraction.attributeType?.name,
            trustAnchor = retraction.trustAnchor?.name, reason = retraction.reason, at = checkNotNull(retraction.retractedAt)
        )
        return accountRetractionRepository.save(retraction)
    }

    /**
     * The audit record of one identification run (ADR-39): which procedure, at which level, in which
     * role (identifying or only correlating, ADR-18), and where to check it - never what it saw.
     * [report] is the tool's own, unfiltered; [ChangeLog.identified] keeps only its references.
     *
     * Also stamps what finds the person again, even after deletion (ADR-39): the search key over
     * the account's VERIFIED name, first name and date of birth - self-reported values never count,
     * or anyone could plant hits under someone else's name - and the register's person id if any.
     * Called after the run's claims are recorded, so they are part of it.
     */
    @Transactional
    fun addIdentification(accountId: Long, method: String, loa: String?, role: String? = null, report: Map<String, Any?> = emptyMap()) {
        val verified = accountClaimRepository.findEstablished(accountId)
            .filter { ClaimSource(it.claimSource.orEmpty()).trustLevel.rank >= TrustLevel.PROVEN.rank }
            .strongestEstablishedValues(PERSON_LOOKUP_ATTRIBUTES)
        val lookupKey = personLookupKey.of(
            verified[AttributeType.FAMILY_NAME], verified[AttributeType.GIVEN_NAMES], verified[AttributeType.BIRTH_DATE]?.let(LocalDate::parse)
        )
        val personId = accountAnchorRepository.findByAccountIdAndAttributeType(accountId, AttributeType.PERSON_ID)?.value
        changeLog.identified(accountId, method, loa, role, report, lookupKey, personId)
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
        /**
         * How the method was added - the proofs of the session that added it and the channel it
         * ran on. Audit evidence only (ADR-39): recorded with the `METHOD_ADDED` event, which
         * outlives the method and the account, never in [details], which does not.
         */
        enrolledUnderAmr: List<String> = emptyList(),
        channel: String? = null,
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
            active.forEach {
                it.deactivate(now)
                changeLog.methodDeactivated(accountId, it.method, MethodDeactivationReason.REPLACED, now)
            }
            // Flushed before the new instance is inserted: the database allows one active instance
            // of a singleton method per account (ux_auth_method_active_singleton), and Hibernate
            // would otherwise insert before it updates.
            accountAuthMethodRepository.saveAllAndFlush(active)
            retractReplacedClaims(accountId, active.mapNotNull { it.id }, replacement = instanceId, now = now)
        } else if (active.any { it.enrollmentRef == enrollmentRef }) {
            // The SAME physical credential re-reported for the SAME account can only be a re-run of
            // an already-completed enrollment (a device is bound to one account at a time,
            // docs/09-dpop.md) - a second row would match every future lookup alike.
            return getProfileOrThrow(accountId)
        }
        changeLog.methodAdded(accountId, method, enrolledUnderAcr, enrolledUnderAmr, channel, now)
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
        findMethodInstance(accountId, methodInstanceId)?.takeIf { it.active }?.let {
            val now = Instant.now()
            it.deactivate(now)
            changeLog.methodDeactivated(accountId, it.method, MethodDeactivationReason.REMOVED_BY_HOLDER, now)
        }
        return getProfileOrThrow(accountId)
    }

    @Transactional(readOnly = true)
    fun findAccount(accountId: Long): AccountProfile? =
        accountRepository.findByIdOrNull(accountId)?.let { toProfile(it) }

    /** Every account id that currently exists - for the demo admin pages only; nothing in the login path may depend on a full list (10 million+ accounts, review 2026-09 P-3). */
    @Transactional(readOnly = true)
    fun allAccountIds(): List<Long> = accountRepository.findAllIds()

    /**
     * Deletes the account row; its anchors, methods, claims and identification details cascade with
     * it - the values go. What survives is the change log without values (ADR-39): this very
     * deletion is its last entry, and the retention period counts from it. This
     * module must never depend on a method module (see [ModuleMetadata]), so the caller is
     * responsible for first cleaning up the credentials [allEnrollmentRefs] points at
     * (docs/05-api.md, Account löschen) and for the account-adjacent orchestrator state.
     */
    @Transactional
    fun deleteAccount(accountId: Long) {
        changeLog.accountDeleted(accountId)
        accountRepository.deleteById(accountId)
        eventPublisher.publishEvent(AccountDeleted(accountId))
    }

    /**
     * Deletes [accountId] only if it is still provisional ([AccountProfile.isProvisional]) - the
     * cleanup after an abandoned registration. The rule is checked HERE, not only by the caller
     * (review 2026-09, M-13): this is the one deletion that skips everything the full deletion
     * (`AccountDeletionService`) tears down, and that is safe only because a provisional account
     * has nothing to tear down - no method, no identity, and therefore never a device link (links
     * are only written after identification, a proof or an enrollment).
     *
     * @return whether the account was deleted.
     */
    @Transactional
    fun deleteProvisionalAccount(accountId: Long): Boolean {
        val account = findAccount(accountId) ?: return false
        if (!account.isProvisional) return false
        deleteAccount(accountId)
        return true
    }

    /**
     * Every enrollmentRef this account's methods ever pointed at - active AND deactivated alike,
     * so a caller cleaning up cross-module credential rows on account deletion doesn't leave a
     * replaced phone number's/device's row behind.
     */
    @Transactional(readOnly = true)
    fun allEnrollmentRefs(accountId: Long): List<EnrollmentRef> =
        accountAuthMethodRepository.findByAccountIdOrderByCreatedAt(accountId).map { it.enrollmentRef }

    /**
     * Whether a method of ANOTHER account points at the same credential row as [enrollmentRef] - a
     * device key rebound to a new account, whose enrollment reused the row by its thumbprint. Such a
     * row must survive this account's deletion or revocation - deleting it would break the other
     * account's method (review 2026-09, Phase F).
     */
    @Transactional(readOnly = true)
    fun isEnrollmentSharedWithOtherAccount(accountId: Long, enrollmentRef: EnrollmentRef): Boolean =
        accountAuthMethodRepository.existsByEnrollmentTypeAndEnrollmentIdAndAccountIdNot(enrollmentRef.type, enrollmentRef.id, accountId)

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
     * name of an Interessent without a register person (ADR-18) and the Keycloak user view both
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
     * Follows a change the Personenverzeichnis reported (ADR-34) for the account bound to that
     * person, if any: the KVNR claim and the Versicherungsnummer anchor are the only things the
     * account stores itself - every other attribute is read live. The old value is retracted (the
     * Personenverzeichnis no longer carries it), the new one recorded; a removed number just ends
     * there - the KVNR may be missing for a while, and without a Versicherungsnummer the person is
     * a Partner, no longer insured with us (roles, ADR-34).
     *
     * A named exception to "an anchor write is paid with the session's proven level": there is no
     * session here, the Personenverzeichnis itself is the authority for these two identifiers -
     * hence [DIRECTORY_ACR]. Only this method, only KVNR and VERSNR, and only for the account
     * bound to exactly this person by its PERSON_ID anchor.
     *
     * @return the account that followed the change, or null when nobody is bound to the person.
     */
    @Transactional
    fun applyDirectoryChange(change: PersonChanged): Long? {
        val accountId = resolveByAnchor(AttributeType.PERSON_ID, change.personId) ?: return null
        if (AttributeType.KVNR in change.changed) {
            retractAttribute(accountId, AttributeType.KVNR, RetractionAnchor.PERSON_DIRECTORY, "KVNR im Personenverzeichnis geändert")
            change.kvnr?.let {
                recordClaims(accountId, listOf(Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, DIRECTORY_ACR)), DIRECTORY_ACR)
            }
        }
        if (AttributeType.INSURANCE_NUMBER in change.changed) {
            retractAttribute(accountId, AttributeType.INSURANCE_NUMBER, RetractionAnchor.PERSON_DIRECTORY, "Versicherungsnummer im Personenverzeichnis geändert")
            change.insuranceNumber?.let {
                releaseFromOtherAccount(AttributeType.INSURANCE_NUMBER, it, keeper = accountId)
                recordClaims(accountId, listOf(Claim(AttributeType.INSURANCE_NUMBER, it, ClaimSource.PERSON_DIRECTORY, DIRECTORY_ACR)), DIRECTORY_ACR)
            }
        }
        return accountId
    }

    /**
     * The Personenverzeichnis is the authority for the Versicherungsnummer (ADR-34): when it assigns
     * [value] to the person bound to [keeper], an account that still holds it as an anchor holds a
     * stale value - the directory moved it, and that account's own change event may simply not have
     * arrived yet. It is withdrawn there, in the directory's name. Otherwise the change would fail on
     * the anchor conflict and be retried forever, never resolving (review 2026-09, M-13).
     */
    private fun releaseFromOtherAccount(type: AttributeType, value: String, keeper: Long) {
        val held = accountAnchorRepository.findByAttributeTypeAndValue(type, type.normalizeAnchorValue(value)) ?: return
        if (held.accountId == keeper) return
        val previousHolder = checkNotNull(held.accountId)
        log.info("{} anchor moved by the Personenverzeichnis: released from account {} for account {}", type.wireName, previousHolder, keeper)
        retractAttribute(previousHolder, type, RetractionAnchor.PERSON_DIRECTORY, "Im Personenverzeichnis einer anderen Person zugeordnet")
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
            personId = anchors[AttributeType.PERSON_ID]?.value,
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

    private companion object {
        /** What the Personenverzeichnis' own word counts as for [applyDirectoryChange] - the anchor floor of both identifiers. */
        val DIRECTORY_ACR = AcrLevel.LOA2

        /** What a person can still tell us years later - the input of [PersonLookupKey]. */
        val PERSON_LOOKUP_ATTRIBUTES = setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
    }
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

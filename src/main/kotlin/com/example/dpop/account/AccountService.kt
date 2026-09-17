package com.example.dpop.account

import com.example.dpop.account.internal.Account
import com.example.dpop.account.internal.AccountAnchor
import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.account.internal.AccountAttribute
import com.example.dpop.account.internal.AccountAttributeRepository
import com.example.dpop.account.internal.AccountIdentification
import com.example.dpop.account.internal.AccountRepository
import com.example.dpop.account.internal.AuthenticationMethod
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.ConsolidationStrategy
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.allowsAnchorReplacement
import com.example.dpop.tool_api.anchorBindingStrength
import com.example.dpop.tool_api.consolidationStrategy
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
 * Fired after any account mutation this service makes - the account-sync mechanism (see
 * `orchestrator.kc.KeycloakAccountSyncListener`, only wired up under the `keycloak` Spring
 * profile) listens for this to keep a mirrored Keycloak user in sync, but this event itself is
 * profile-agnostic and free to publish always: nothing here depends on who's listening.
 * [accountId] alone (not a snapshot) - a listener that needs the current state re-reads it via
 * [AccountService.findAccount], the same way every other reader does.
 */
data class AccountChanged(val accountId: Long)

/** Fired once an account row is actually gone - unlike [AccountChanged], nothing left to re-read, so this carries everything a listener gets. */
data class AccountDeleted(val accountId: Long)

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val accountAttributeRepository: AccountAttributeRepository,
    private val accountAnchorRepository: AccountAnchorRepository,
    private val eventPublisher: ApplicationEventPublisher
) : AccountDirectory {

    private val log = LoggerFactory.getLogger(AccountService::class.java)

    /**
     * Delegating convenience wrapper (docs/ideen/account-attribute-und-trust-vereinheitlichen.md,
     * Paket 4) around [recordClaims] for the common single-claim case.
     */
    @Transactional
    fun recordClaim(accountId: Long, claim: Claim) = recordClaims(accountId, listOf(claim))

    /**
     * The claims-log write path (docs/ideen/claims-modell-und-vertrauensanker.md, Phase 1) for
     * every [claims] a single completed tool run asserted, applied together - the typed
     * counterpart of what `Completed.Identified`/`Completed.Enrolled` carry as [Claim]s. Never
     * overwrites a prior claim; the log is provenance. At most one claim per [AttributeType] -
     * same contract `assertClaimsCovered` already checks upstream, re-checked here so this
     * method is safe to call on its own. [AttributeType.consolidationStrategy] decides what
     * happens beyond logging, per claim: [ConsolidationStrategy.OwnedColumn] additionally writes
     * the canonical projection column ([consolidateOwnedColumn]), its anchor and the
     * [AccountChanged] event; [ConsolidationStrategy.ExternalLiveLookup] does nothing further
     * here - authority for that value lives at ext_stammdaten, reachable via this account's own
     * `personId`, so the log entry above is already the only local trace this claim needs (see
     * that strategy's own doc for why nothing per-attribute is ever cached).
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
                    trustAnchor = claim.source.value,
                    establishedLoa = claim.establishedLoa?.value,
                    establishedAt = establishedAt
                )
            )
            when (claim.attributeType.consolidationStrategy()) {
                ConsolidationStrategy.OwnedColumn -> consolidateOwnedColumn(accountId, claim.attributeType, claim.value, establishedAt)
                ConsolidationStrategy.ExternalLiveLookup -> {}
            }
        }
    }

    /**
     * Synchronously consolidates an [ConsolidationStrategy.OwnedColumn] attribute - the account's
     * own column(s) ([Account.applyOwnedColumn]), its anchor if [AttributeType.anchorBindingStrength]
     * is non-null, and the [AccountChanged] event (Keycloak mirrors these attributes, the sync
     * listener re-reads the account). Generic over every `OwnedColumn` type (`EMAIL`, `PERSON_ID`)
     * and every caller, [recordClaim]/[recordClaims] included - `demo_seed`'s `KcDemoAccountSeeder`
     * goes through the same path too, with `ClaimSource.DEMO_BOOTSTRAP` naming its provenance.
     */
    private fun consolidateOwnedColumn(accountId: Long, type: AttributeType, value: String, establishedAt: Instant) {
        val account = getOrThrow(accountId)
        // The anchor goes FIRST: it is the uniqueness authority for the value, so a
        // cross-account conflict must abort before the projection column is written, not get
        // undone by a rollback afterwards. Stored in the anchor's normalized form while the
        // raw column stays raw - same ownership, one write.
        if (type.anchorBindingStrength != null) recordAnchor(accountId, type, value, establishedAt)
        account.applyOwnedColumn(type, value, establishedAt)
        accountRepository.save(account)
        eventPublisher.publishEvent(AccountChanged(accountId))
    }

    /**
     * Materializes an anchor row - the resolve-identity projection, not provenance (that
     * stays in `account_attribute`). Idempotent when this account already holds the value. A
     * value held by ANOTHER account is rejected outright ([IdentityConflictException]), never
     * re-assigned and never silently skipped: ADR-11 makes a cross-account conflict an upstream
     * rejection, and swallowing it here produced exactly the divergence the anchor exists to
     * prevent - the caller's own projection column (`account.email`) was still written, so the
     * account claimed a value whose anchor pointed at a DIFFERENT account, and the two lookup
     * paths over it disagreed from then on. Throwing rolls the whole claim back instead, log
     * entry included, leaving one consistent answer to "who owns this value". A new value for
     * THIS account's own anchor re-binds it ONLY if [AttributeType.allowsAnchorReplacement] says
     * so (`EMAIL`: the existing anchor follows the account's latest established claim).
     * For a type where it does not (`PERSON_ID`: immutable after first binding), re-asserting the
     * SAME value is idempotent (handled above, before this ever runs), but a DIFFERENT value for
     * an account that already holds one is rejected the same way a cross-account conflict is -
     * both are the identity model refusing to silently overwrite an established binding.
     *
     * A rebind UPDATES the existing row in place rather than deleting and re-inserting: Hibernate
     * flushes insertions before deletions by default, so a delete-then-insert pair on the SAME
     * `(account_id, attribute_type)` briefly has both rows present at flush time and trips
     * `ux_account_anchor_account_type` (V38) - a real unique-constraint violation a mocked test
     * cannot catch, only a real DB one did (`AccountServiceDbTest`).
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
        val profile = toProfile(accountRepository.save(Account(personId = null, createdAt = Instant.now())))
        eventPublisher.publishEvent(AccountChanged(profile.accountId))
        return profile
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
        val profile = toProfile(accountRepository.save(account))
        eventPublisher.publishEvent(AccountChanged(accountId))
        return profile
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
        // canAccountReach/authCandidates/resolveAcr would inconsistently see both. Multi-instance
        // methods are exempt on purpose - that coexistence is the whole point there.
        if (!allowsMultipleInstances) {
            account.authenticationMethods.filter { it.method == method && it.active }.forEach { it.active = false }
        } else if (account.authenticationMethods.any { it.method == method && it.active && extractEnrollmentRef(it.details) == enrollmentRef }) {
            // The SAME physical credential (identical enrollmentRef) re-reported for the SAME
            // account - a device is only ever bound to one account at a time (docs/09-dpop.md), so
            // this can only be a re-run of an already-completed enrollment (the underlying tool
            // handler treats re-enrolling the same key as idempotent, see EnrollDeviceToolHandler),
            // never a genuinely new instance. Adding a second row here would leave two active
            // entries for the identical credential, both matching every future lookup alike.
            return toProfile(account)
        }
        val mergedDetails = details + mapOf("enrollmentRef" to mapOf("type" to enrollmentRef.type, "id" to enrollmentRef.id))
        account.addAuthenticationMethod(
            AuthenticationMethod(
                method, true, Instant.now(), enrolledUnderAcr, mergedDetails,
                id = UUID.randomUUID().toString(), label = label
            )
        )
        val profile = toProfile(accountRepository.save(account))
        eventPublisher.publishEvent(AccountChanged(accountId))
        return profile
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
        val profile = toProfile(accountRepository.save(account))
        eventPublisher.publishEvent(AccountChanged(accountId))
        return profile
    }

    @Transactional(readOnly = true)
    fun findAccount(accountId: Long): AccountProfile? =
        accountRepository.findByIdOrNull(accountId)?.let { toProfile(it) }

    /** Every account id that currently exists - the full-reconciliation counterpart of the per-event [AccountChanged]/[AccountDeleted] (see `KeycloakAccountSyncService`'s explicit "Sync with Keycloak" action, which also needs to find KEYCLOAK-side orphans nothing here still references). */
    @Transactional(readOnly = true)
    fun allAccountIds(): List<Long> = accountRepository.findAllIds()

    /**
     * Deletes only the account row itself - this module must never depend on a method module
     * (see [ModuleMetadata]), so the caller is responsible for first cleaning up whatever
     * credentials the account's own `authenticationMethods` referenced (docs/05-api.md, Account
     * löschen), and for the account-adjacent orchestrator state (DeviceAccountLink, AuthContext).
     */
    @Transactional
    fun deleteAccount(accountId: Long) {
        accountRepository.deleteById(accountId)
        eventPublisher.publishEvent(AccountDeleted(accountId))
    }

    /**
     * Every enrollmentRef this account's `authenticationMethods` entries ever pointed at - active
     * AND already-superseded/deactivated alike, so a caller cleaning up cross-module credential
     * rows on account deletion doesn't leave a replaced phone number's/device's row behind.
     */
    @Transactional(readOnly = true)
    fun allEnrollmentRefs(accountId: Long): List<EnrollmentRef> =
        findAccount(accountId)?.authenticationMethods?.mapNotNull { extractEnrollmentRef(it.details) } ?: emptyList()

    /**
     * The [EnrollmentRef] for ONE method instance, not the whole account (unlike
     * [allEnrollmentRefs]) - for a caller revoking a single credential (e.g. a device rebind,
     * `AccountDeletionService.revokeMethod`) rather than deleting the account outright.
     */
    @Transactional(readOnly = true)
    fun enrollmentRefFor(accountId: Long, methodInstanceId: String): EnrollmentRef? =
        findAccount(accountId)?.authenticationMethods?.firstOrNull { it.id == methodInstanceId }?.let { extractEnrollmentRef(it.details) }

    @Transactional(readOnly = true)
    fun findActiveMethod(accountId: Long, method: String): AuthMethodView? =
        findAccount(accountId)?.authenticationMethods?.firstOrNull { it.active && it.method == method }

    /** All active instances of [method] - plural sibling of [findActiveMethod] for multi-instance methods (e.g. several active `device` entries, one per physical device). */
    @Transactional(readOnly = true)
    fun findActiveMethods(accountId: Long, method: String): List<AuthMethodView> =
        findAccount(accountId)?.authenticationMethods?.filter { it.active && it.method == method } ?: emptyList()

    // AccountDirectory (tool_api) -------------------------------------------------------------

    override fun resolveByAnchor(type: AttributeType, value: String): Long? =
        accountAnchorRepository.findByAttributeTypeAndValue(type, type.normalizeAnchorValue(value))?.accountId

    override fun anchorValue(accountId: Long, type: AttributeType): String? {
        check(type.anchorBindingStrength != null) { "$type is not a local account anchor" }
        return accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.value
    }

    override fun activeEnrollment(accountId: Long, method: String): EnrollmentRef? =
        findActiveMethod(accountId, method)?.let { extractEnrollmentRef(it.details) }

    override fun activeInstanceEnrollment(accountId: Long, method: String, matchesCaller: (Map<String, Any?>?) -> Boolean): EnrollmentRef? =
        findActiveMethods(accountId, method)
            .firstOrNull { matchesCaller(it.details) }
            ?.let { extractEnrollmentRef(it.details) }

    /** The inverse of [addAuthenticationMethod]'s `mergedDetails` write - reads the same shape back out. */
    private fun extractEnrollmentRef(details: Map<String, Any?>?): EnrollmentRef? {
        val raw = details?.get("enrollmentRef") as? Map<*, *> ?: return null
        val type = raw["type"] as? String ?: return null
        val id = raw["id"] as? String ?: return null
        return EnrollmentRef(type, id)
    }

    private fun getOrThrow(accountId: Long): Account =
        accountRepository.findByIdOrNull(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")

    private fun toProfile(account: Account): AccountProfile = AccountProfile(
        accountId = requireNotNull(account.id) { "Account has no id" },
        personId = account.personId,
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

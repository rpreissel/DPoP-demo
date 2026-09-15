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
import com.example.dpop.tool_api.AnchorType
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.EnrollmentRef
import java.time.Instant
import java.util.UUID
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

    /** Only the orchestrator calls this, right after Completed.Identified (docs/04-orchestrierung.md). */
    @Transactional
    fun findOrCreateAccount(personId: Long): AccountProfile {
        val existing = accountRepository.findByPersonId(personId)
        val account = existing ?: Account(personId, Instant.now())
        val profile = toProfile(accountRepository.save(account))
        if (existing == null) eventPublisher.publishEvent(AccountChanged(profile.accountId))
        return profile
    }

    /**
     * Appends a claim to the account's identity log
     * (docs/ideen/claims-modell-und-vertrauensanker.md, Phase 1) - the typed counterpart of what
     * `Completed.Identified`/`Completed.Enrolled` now carry as [Claim]. Never overwrites a prior
     * claim; the log is provenance. EMAIL is the first claim type consolidated synchronously:
     * [consolidateEmail] writes the canonical projection column, the anchor and the
     * [AccountChanged] event. Every other attribute keeps `account`'s own columns as the
     * actively consolidated projection, written straight through by the existing paths
     * (`findOrCreateAccount`, `bindPersonId`); nothing reads their log rows back yet. When
     * something eventually does, consolidation prefers anchor class over recency - recency is
     * only the tiebreaker within one anchor class (ADR-11, docs/12-entscheidungen.md).
     * Anchor-role claims additionally materialize their `account_anchor` row ([recordAnchor]) -
     * the resolve-identity projection, not provenance.
     */
    @Transactional
    fun recordClaim(accountId: Long, claim: Claim) {
        val establishedAt = Instant.now()
        accountAttributeRepository.save(
            AccountAttribute(
                accountId = accountId,
                attributeType = claim.attributeType.wireName,
                value = claim.value,
                trustAnchor = claim.trustAnchor.value,
                establishedLoa = claim.establishedLoa?.value,
                establishedAt = establishedAt
            )
        )
        if (claim.attributeType == AttributeType.EMAIL) {
            consolidateEmail(accountId, claim.value, establishedAt)
        } else {
            AnchorType.of(claim.attributeType)?.let { recordAnchor(accountId, it, claim.value, establishedAt) }
        }
    }

    /**
     * Synchronously consolidates the canonical email projection - column, anchor and event -
     * from a proven EMAIL claim (enroll-email via [recordClaim]) or a bootstrap seed
     * ([confirmEmail]). Fires [AccountChanged] because Keycloak mirrors this attribute (the
     * sync listener re-reads the account). Behavior is identical to what `confirmEmail` did
     * before the claims write path went generic.
     */
    private fun consolidateEmail(accountId: Long, email: String, confirmedAt: Instant): AccountProfile {
        val account = getOrThrow(accountId)
        account.email = email
        account.emailConfirmedAt = confirmedAt
        val profile = toProfile(accountRepository.save(account))
        // The email anchor follows the canonical projection column - same ownership, one
        // write: stored in the anchor's normalized form while the raw column stays raw.
        recordAnchor(accountId, AnchorType.Email, email, confirmedAt)
        eventPublisher.publishEvent(AccountChanged(accountId))
        return profile
    }

    /**
     * Materializes an anchor row - the resolve-identity projection, not provenance (that
     * stays in `account_attribute`). Idempotent when this account already holds the value; a
     * value held by ANOTHER account is never re-assigned (ADR-11: cross-account conflicts
     * stay upstream rejections, never silent merges) - the claim itself is still logged, this
     * account's anchor just stays whatever it was. A new value for THIS account's own anchor
     * re-binds it: the old row goes, the anchor follows the account's latest established
     * claim.
     */
    private fun recordAnchor(accountId: Long, type: AnchorType, value: String, establishedAt: Instant) {
        val normalized = type.normalize(value)
        if (accountAnchorRepository.existsByAnchorTypeAndValue(type.wireName, normalized)) return
        accountAnchorRepository.findByAccountIdAndAnchorType(accountId, type.wireName)
            ?.let { accountAnchorRepository.delete(it) }
        accountAnchorRepository.save(
            AccountAnchor(
                accountId = accountId,
                anchorType = type.wireName,
                value = normalized,
                establishedAt = establishedAt
            )
        )
    }

    /**
     * A fresh account with no person behind it yet (REGISTER "Enrollment zuerst",
     * docs/04-orchestrierung.md) - created lazily on the first completed enrollment, never
     * upfront, so a channel that never gets that far never leaves an orphan row behind.
     * Identification remains entirely optional and can bind [bindPersonId] at any later point, not
     * just right after registration.
     */
    @Transactional
    fun createUnidentifiedAccount(): AccountProfile {
        val profile = toProfile(accountRepository.save(Account(personId = null, createdAt = Instant.now())))
        eventPublisher.publishEvent(AccountChanged(profile.accountId))
        return profile
    }

    /** Same lookup [findOrCreateAccount] already does internally, exposed for the merge-conflict check before [bindPersonId]. */
    @Transactional(readOnly = true)
    fun findAccountByPersonId(personId: Long): AccountProfile? =
        accountRepository.findByPersonId(personId)?.let { toProfile(it) }

    /**
     * Identifies a previously unidentified account (docs/04-orchestrierung.md, REGISTER
     * "Enrollment zuerst") - the caller must already have checked [findAccountByPersonId] itself
     * for a merge conflict (a DIFFERENT account already owning this person); this method only
     * guards the account-local invariant that an already-identified account's `personId` is never
     * silently overwritten.
     */
    @Transactional
    fun bindPersonId(accountId: Long, personId: Long): AccountProfile {
        val account = getOrThrow(accountId)
        check(account.personId == null) { "Account $accountId is already identified as person ${account.personId}" }
        account.personId = personId
        val profile = toProfile(accountRepository.save(account))
        eventPublisher.publishEvent(AccountChanged(accountId))
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
    fun allAccountIds(): List<Long> = accountRepository.findAll().mapNotNull { it.id }

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
    fun findAccountByEmail(email: String): AccountProfile? =
        accountRepository.findByEmail(email)?.let { toProfile(it) }

    @Transactional(readOnly = true)
    fun existsByEmail(email: String): Boolean = accountRepository.existsByEmail(email)

    /**
     * Bootstrap special case of the claims write path - the live path is generic:
     * `JourneyService`'s `Action.AdoptCredential` handling records every enrolled claim via
     * [recordClaim], and an EMAIL claim lands in [consolidateEmail]. What remains here is
     * `demo_seed`'s `KcDemoAccountSeeder`, once at startup, to give the `keycloak` profile's
     * seeded test persons a confirmed email before any real enrollment ever runs.
     *
     * The confirmed email is still the account's identifier, not a swappable credential -
     * `auth_email` no longer depends on `account` at all since it reads and writes through the
     * generic anchor ports and claims.
     */
    @Transactional
    fun confirmEmail(accountId: Long, email: String): AccountProfile =
        consolidateEmail(accountId, email, Instant.now())

    @Transactional(readOnly = true)
    fun findActiveMethod(accountId: Long, method: String): AuthMethodView? =
        findAccount(accountId)?.authenticationMethods?.firstOrNull { it.active && it.method == method }

    /** All active instances of [method] - plural sibling of [findActiveMethod] for multi-instance methods (e.g. several active `device` entries, one per physical device). */
    @Transactional(readOnly = true)
    fun findActiveMethods(accountId: Long, method: String): List<AuthMethodView> =
        findAccount(accountId)?.authenticationMethods?.filter { it.active && it.method == method } ?: emptyList()

    // AccountDirectory (tool_api) -------------------------------------------------------------

    override fun resolveAccountByEmail(email: String): Long? = findAccountByEmail(email)?.accountId

    override fun resolveByAnchor(type: AnchorType, value: String): Long? =
        accountAnchorRepository.findByAnchorTypeAndValue(type.wireName, type.normalize(value))?.accountId

    override fun anchorValue(accountId: Long, type: AnchorType): String? =
        accountAnchorRepository.findByAccountIdAndAnchorType(accountId, type.wireName)?.value

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

package com.example.dpop.orchestrator.journey

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.account.RetractionAnchor
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.policy.EvidenceAxis
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.kernel.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.toCoreEvidence
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.IdentityResolver
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.assertClaimsCovered
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.example.dpop.orchestrator.kernel.AuthIntent

/**
 * The acting phase of a transition: the [Action]s a [Transition.Perform] can carry, actually
 * executed. Split out of [JourneyService] along the machine's own phase boundary - that service
 * DECIDES and ROUTES (which strategy, which transition, where next), this class WRITES (accounts,
 * claims, credentials, device links, revocations).
 *
 * The split is the same one [IntentStrategy] already makes one level up, continued: a strategy
 * decides but never acts, and the service that drives it now no longer mixes the driving with the
 * acting either. Every side effect a journey can have is reachable from exactly one file, so
 * "no intent can forget the ACR cap or skip the audit trail" stays inspectable.
 *
 * Deliberately NOT here: anything that would call back into the machine. [performAction] only
 * ever writes and returns - it never advances a journey, never routes, never starts a
 * sub-journey. That one-way dependency is what keeps the recursion in
 * [JourneyService.applyTransition] the only recursion there is.
 */
@Component
@Transactional
class JourneyActionExecutor(
    private val journeyRepository: AuthJourneyRepository,
    private val accountService: AccountService,
    private val identityResolver: IdentityResolver,
    private val authContextService: AuthContextService,
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val accountDeletionService: AccountDeletionService,
    private val toolRegistry: ToolHandlerRegistry,
    private val authPolicy: AuthPolicy,
    private val journeyRecorder: JourneyRecorder,
    private val contextFactory: JourneyContextFactory
) {
    /**
     * The [Action]s a [Transition.Perform] can carry, actually executed. The first four
     * (tool-outcome) variants carry their own [com.example.dpop.tool_spi.ToolDescriptor]/
     * [com.example.dpop.tool_spi.ToolOutcome] (see [Action]'s own doc), so
     * [JourneyRecorder.recordToolCompletion] - the MethodEvidence bookkeeping every one of them
     * needs alike - reads it straight off the action instead of needing it passed in separately.
     *
     * @return a demo-only notice to merge into the eventual response's `demo` block (see
     * [com.example.dpop.tool_spi.ToolOutcome.InProgress.demo]), or `null` when this action has none. Currently
     * only [Action.AdoptCredential] ever returns one - see [performAdoptCredential] for why.
     */
    fun perform(journey: AuthJourney, channel: ChannelSession, action: Action): Map<String, Any?>? {
        var demoNotice: Map<String, Any?>? = null
        when (action) {
            is Action.RecordIdentification -> performRecordIdentification(journey, channel, action)
            is Action.AdoptAttestation -> performAdoptAttestation(journey, channel, action)
            is Action.AdoptCredential -> demoNotice = performAdoptCredential(journey, channel, action)
            is Action.AcceptProof -> performAcceptProof(journey, channel, action)
            is Action.ApplyRestoredEvidence ->
                journeyRecorder.mergeEvidence(journey, channel, action.source, action.methods)
            // The tool already performed its own domain effect (writing QrLoginRequest) before
            // reporting Approved - nothing left to do here beyond the same bookkeeping every
            // tool-outcome action gets. achievedAcr/amr are empty by construction (see
            // ToolOutcome.Completed.Approved's own doc), so this never changes what the channel's
            // own evidence says it has proven.
            is Action.RecordApproval -> journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
            is Action.RevokeAuthMethod -> removeMethod(journey, channel, action.methodInstanceId)
            is Action.RetractAttribute -> performRetractAttribute(journey, channel, action.attributeType)
            is Action.LinkDevice -> performLinkDevice(journey, channel)
            is Action.DeleteAccount -> performDeleteAccount(journey, channel)
        }
        return demoNotice
    }

    /**
     * Central identity resolution (docs/ideen/claims-modell-und-vertrauensanker.md,
     * "Identitaetsauflösung & Matching"): the account module owns the matching policy, this
     * service only governs the consequences. THE single handler for [Action.RecordIdentification],
     * covering both origins: "nothing bound yet, adopt or create" and "something already bound,
     * may only extend/merge under [accountOf]'s rule" are not a strategy's choice of which Action
     * to build - they are read from [journey]/[channel] right here, every single time. So neither
     * a strategy nor any combination of tools and order it might run them in can construct a
     * variant that skips the merge-safety check ([accountOf]) when an account is already in hand.
     */
    private fun performRecordIdentification(journey: AuthJourney, channel: ChannelSession, action: Action.RecordIdentification) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        val inHand = journey.accountId ?: channel.accountId
        // A MethodRole.CORRELATION step (ident-kvnr, ADR-18) proves nothing about the subject
        // itself - it only turns a typed number into a register-vouched PERSON_ID, which is
        // exactly what that role declares. An IDENTIFICATION tool may bind on its own strength
        // (ident-fsc's mailed code IS possession of something sent to that very person); a
        // correlation step may not: its whole security argument is that the register's person
        // matches what THIS account already had attested. Without the check, attesting yourself
        // and then typing a stranger's number would bind their anchor here, whenever that
        // stranger has no account of their own yet. A correlation step always runs against an
        // account already in hand (RegisterState.Assigning's own invariant) - crash loudly if
        // that is somehow violated rather than silently opening a fresh account for it.
        if (action.tool.role == MethodRole.CORRELATION) {
            val correlatingAccount = checkNotNull(inHand) { "Correlation without a known account under ${journey.intent}" }
            val account = checkNotNull(accountService.findAccount(correlatingAccount)) {
                "Correlation against an account that does not exist: $correlatingAccount"
            }
            // Attaching a person to an account that ALREADY has one is not a correlation, it is a
            // change of identity - and one bought with no proof at all. Refused outright rather
            // than matched: there is no value of the typed number that would make it legitimate.
            // Today only RegisterStrategy offers the step, and only while personId is null; this
            // makes that a property of the act instead of of the strategy that happens to offer it.
            if (account.personId != null) {
                throw IdentityConflictException(Text("Dieses Konto ist bereits einer Person zugeordnet"))
            }
            // Unconditional, and the reason the step is allowed to exist: its whole security
            // argument is that the register's person matches what THIS account already had
            // attested. Without it, attesting yourself and then typing a stranger's number would
            // bind their anchor here whenever that stranger has no account of their own yet.
            // A run that resolves nobody has nothing to correlate and must not pass silently.
            val claimedPersonId = checkNotNull(action.outcome.personId) {
                "${action.tool.toolId} completed as a correlation without resolving a person"
            }
            if (!identityResolver.attestedIdentityMatches(correlatingAccount, claimedPersonId)) {
                throw IdentityConflictException(Text("Die Versichertennummer gehoert nicht zu der nachgewiesenen Identitaet"))
            }
        }
        val resolution = identityResolver.resolve(action.outcome.claims.toSet())
        val accountId = when (resolution) {
            // Whenever an account is already in hand (`Identifying` re-entered after
            // `AuthChoice -> Identifying: alle abgelehnt`; RE_IDENTIFY; ident-kvnr's correlation
            // step), this ALWAYS routes through the same [accountOf] gate - never a bespoke
            // comparison. That gate is what rejects two already-credentialed accounts silently
            // merging (its own doc: "a decision for an explicit account merge, never a side
            // effect of an identification step").
            is Resolution.ExistingAccount ->
                if (inHand == null) resolution.accountId else accountOf(journey, channel, inHand, resolution.accountId)
            // Nothing resolved - the attested subject has no account yet. Which account this run
            // then writes to is a declared rule, not a default:
            Resolution.Unresolved -> {
                val inHandAccount = inHand?.let { accountService.findAccount(it) }
                when {
                    // Nothing in hand: a fresh account. It and its claims share this journey
                    // transaction, including rollback.
                    inHandAccount == null -> accountService.createUnidentifiedAccount().accountId
                    // An account without a person binding takes the attestation (ADR-10): this is
                    // the REGISTER "Enrollment zuerst" account finally getting its identity, a
                    // provisional one being attested a second time, or the account a correlation
                    // step is extending (CORRELATION always reaches here with inHand present and
                    // no register person of its own yet). Opening a SECOND account beside it
                    // would silently split one run across two.
                    inHandAccount.isUnidentified -> inHandAccount.accountId
                    // An identified account plus an attestation that resolves to nobody means a
                    // DIFFERENT person. A device-recognized channel reaches this legitimately -
                    // somebody else registering on a linked phone (docs/04-orchestrierung.md #2,
                    // "Zweitaccount"): that run opens its own account, and the device-rebind
                    // question follows later.
                    journey.accountId == null -> accountService.createUnidentifiedAccount().accountId
                    // This journey bound that identified account itself, so there is no second
                    // account to fall back to - mixing a stranger's attested identity into it is
                    // the one thing that must never happen quietly.
                    else -> throw IdentityConflictException(
                        Text("Die bezeugte Identitaet gehoert nicht zu dem Konto dieser Sitzung")
                    )
                }
            }
        }
        bindAccount(journey, channel, accountId)
        // An identification's own achieved level IS what this session proved about the identity -
        // the figure AnchorRule.acrFloor prices the PERSON_ID anchor against.
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = action.outcome.achievedAcr ?: AcrLevel.NONE)
        journeyRecorder.recordIdentification(journey, channel, action.tool, action.outcome)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    /**
     * Which account a confirmed identification actually writes to (ADR-20). The rule is one
     * sentence: **the provisional account is absorbed into the other one**, whichever of the two
     * it is; if neither is provisional, nothing moves. Declared outcomes, no default:
     *
     * - Resolution found nothing, or found the account already in hand: that account.
     * - Two accounts, and the one IN HAND is provisional
     *   ([com.example.dpop.account.AccountProfile.isProvisional]): that is the ident-first case -
     *   the journey had to open a placeholder for an attestation that resolved nobody, and the
     *   correlation step then found the real account. The journey moves over and takes the
     *   attestation along.
     * - Two accounts, and the RESOLVED one is provisional: the mirror image, which is what the
     *   "Enrollment zuerst" registration runs into. The account in hand is the real one (it holds
     *   the credentials this run just created); the resolved one is a placeholder an earlier,
     *   abandoned eID run left behind, found again through its `restricted_id` anchor (ADR-19).
     *   Without this branch that leftover would permanently block its own card from ever
     *   identifying a durable account. Here the journey stays put and absorbs the placeholder -
     *   no rebind, nothing to re-point.
     * - Neither is provisional: rejected. A credential was enrolled or a person bound on both, so
     *   two real accounts would be merging - a decision for an explicit account merge, never a
     *   side effect of an identification step.
     *
     * Both provisional is the first case: moving to the resolved account keeps the anchor that
     * did the resolving where the rest of the stock expects it.
     */
    private fun accountOf(journey: AuthJourney, channel: ChannelSession, inHand: Long, resolved: Long?): Long {
        if (resolved == null || resolved == inHand) return inHand
        val inHandAccount = accountService.findAccount(inHand)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${inHand}")
        if (inHandAccount.isProvisional) {
            rebindAccount(journey, channel, from = inHand, to = resolved)
            accountService.absorbProvisionalAccount(inHand, resolved)
            return resolved
        }
        val resolvedAccount = accountService.findAccount(resolved)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${resolved}")
        if (resolvedAccount.isProvisional) {
            accountService.absorbProvisionalAccount(resolved, inHand)
            return inHand
        }
        throw IdentityConflictException(Text("Identification claims resolve to a different account"))
    }

    /**
     * Moves a running channel from the provisional account it holds to the one an assignment
     * resolved (ADR-20), BEFORE that account is absorbed and deleted - everything pointing at the
     * old id has to be re-pointed while it still exists.
     *
     * The evidence trail moves with its account pointer but is NOT reset
     * ([AuthEvidenceService.rebindToAccount]): what this session proved, it proved, so the eID
     * run behind the attestation keeps counting. The device link moves too when it points at the
     * yielding account - it is the one thing that outlives a journey
     * ([JourneyService.deleteIfAbandonedUnidentified] relies on that), and leaving it behind
     * would hand a deleted account id to the next FAST_ACCESS run.
     */
    private fun rebindAccount(journey: AuthJourney, channel: ChannelSession, from: Long, to: Long) {
        journey.accountId = to
        channel.accountId = to
        channel.authEvidenceId?.let { authEvidenceService.rebindToAccount(it, to) }
        if (channel.channel == ChannelType.APP) {
            channel.bindingKeyRef?.let { bindingKeyRef ->
                if (sessionManagementService.findLinkedAccountId(bindingKeyRef) == from) {
                    sessionManagementService.linkDeviceToAccount(bindingKeyRef, to)
                }
            }
        }
        sessionManagementService.updateChannelSession(channel)
        journeyRepository.save(journey)
    }

    /**
     * An attested attribute (docs/12-entscheidungen.md, ADR-16 follow-up): the claims land in the
     * account's log and consolidate their anchor, and that is all. No method instance, so
     * confirming an address no longer makes email an authentication method by accident; no device
     * binding, because nothing was created here that this device could later be recognized by.
     *
     * The outcome carries no `amr` by construction ([com.example.dpop.tool_spi.ToolOutcome.Completed.Attested]),
     * so `recordToolCompletion` adds no evidence and the channel's ACR/AMR balance is untouched -
     * an attestation says who the account is reachable as, never that someone just authenticated.
     */
    private fun performAdoptAttestation(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptAttestation) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        // Lazily, exactly like an enrollment (see performAdoptCredential): under REGISTER
        // "Enrollment zuerst" the address is attested as the very FIRST step, before any credential
        // exists - so this is regularly the call that brings the account into being. A channel that
        // never gets further leaves no orphan behind (deleteIfAbandonedUnidentified).
        val inHand = journey.accountId ?: channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Attested without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        val accountId = accountOfAttestation(journey, channel, inHand, action, coreEvidence)
        // The same capped figure an enrollment is stamped with - an anchor write is priced by what
        // the session actually proved (AnchorRule.acrFloor), never by the tool's ceiling.
        val environmentAcr = authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId))
        val provenAcr = if (environmentAcr == AcrLevel.NONE) AcrLevels.DEFAULT_REQUIRED_ACR else environmentAcr
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = provenAcr)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    /**
     * An attested anchor value that resolves to ANOTHER account may move this session there ONLY
     * if THIS session has already proven a real Identification (ident-fsc/ident-eid -
     * [EvidenceAxis.IDENTITY] evidence, [DefaultAuthPolicy]'s own IAL/AAL split) earlier in the
     * SAME journey - never on the strength of the attestation alone. A mere attestation (e.g.
     * `confirm-email`) is deliberately weaker than an identification - the glossary's own
     * "unbescheinigtes/schwaches Identifizierungsmittel" distinction for email applies here
     * directly - and must never by itself be enough to move a session onto another account.
     *
     * This is what makes REGISTER "Enrollment zuerst" safe: its very FIRST step is `confirm-email`,
     * before any identification ever ran, so [hasIdentification] is false and this always rejects -
     * closing the account-takeover gap where a brand-new session could re-confirm somebody else's
     * already-established address and get silently bound to their account, credentials and all,
     * without ever proving possession of any of them.
     *
     * Once an identification DID run this session (the eID-with-no-register-match case, ADR-18:
     * `ident-eid` attests an identity but finds no KVNR, and `confirm-email` is then used to locate
     * the durable account that identity belongs to), the extra condition an identification alone
     * does not need still applies: the attested identity must FIT the account being resolved.
     * Possession of an address says "this mailbox is mine", never "I am that person" - so where the
     * target account has a register person, the identity this session attested is checked against
     * that person's master data ([IdentityResolver.attestedIdentityMatches], the same guard ADR-18
     * puts in front of the correlation step). Without it, whoever controls a mailbox could hang
     * their own eID claims on a stranger's account.
     */
    private fun accountOfAttestation(
        journey: AuthJourney,
        channel: ChannelSession,
        inHand: Long,
        action: Action.AdoptAttestation,
        evidence: AuthEvidence
    ): Long {
        val resolved = (identityResolver.resolve(action.outcome.claims.toSet()) as? Resolution.ExistingAccount)?.accountId
        if (resolved == null || resolved == inHand) return inHand
        val hasIdentification = evidence.factors.any { it.axis == EvidenceAxis.IDENTITY }
        if (!hasIdentification) {
            throw IdentityConflictException(Text("Diese Adresse gehoert bereits zu einem anderen Konto"))
        }
        accountService.findAccount(resolved)?.personId?.let { personId ->
            if (!identityResolver.attestedIdentityMatches(inHand, personId)) {
                throw IdentityConflictException(Text("Diese Adresse gehoert zu einer anderen Person"))
            }
        }
        return accountOf(journey, channel, inHand, resolved)
    }

    private fun performAdoptCredential(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptCredential): Map<String, Any?>? {
        val enrolled = action.outcome
        // Same declaration check as the identity branches - the descriptor↔handler
        // contract holds for every adopting tool, and the claims are recorded right
        // below.
        assertClaimsCovered(action.tool, enrolled.claims)
        // Enrollment with no account yet (REGISTER "Enrollment zuerst",
        // docs/04-orchestrierung.md) - one is created lazily, right here, on the FIRST
        // completed enrollment: no enroll tool's own PATCH handler needs an account to
        // already exist mid-flow, so this is always safe to defer to here. A channel
        // that never gets this far leaves no orphan account behind
        // (`deleteIfAbandonedUnidentified`, `fallBack`).
        val accountId = journey.accountId ?: channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Enrolled without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        // `label` is lifted into its own field rather than staying in the generic details
        // blob, so the API can surface it without clients reaching into details.
        val label = enrolled.auditDetails?.get("label") as? String
        // Generated here rather than by addAuthenticationMethod below, because the claims are
        // recorded FIRST (the acr computation between the two deliberately reads the account
        // including them) and each has to name the instance that established it, so revoking the
        // method can retract exactly that later (ADR-12).
        val methodInstanceId = UUID.randomUUID()
        // Every claim this enrollment asserted lands in the account's identity log
        // (AccountService.recordClaims, a few lines below); an EMAIL claim additionally
        // consolidates its anchor and fires the AccountChanged event. Done here, before this
        // method returns, so the very next context rebuild (JourneyEvent.ActionCompleted) already
        // sees it.
        // What the environment already established BEFORE this completion (recordToolCompletion
        // for THIS one hasn't run yet). "none" only ever means literally nothing backs this
        // session yet (REGISTER "Enrollment zuerst" with no identification at all,
        // docs/04-orchestrierung.md) - falls back to the flat baseline floor, never to this
        // tool's OWN declared strength: a self-registered credential with nothing else
        // corroborating it (no identification, no other factor) is exactly the "self-
        // asserted, unproofed" case, regardless of how strong the tool's own maxAcr
        // theoretically is (e.g. enroll-device declares loa2 on its own - letting that
        // stand unchallenged here would grant loa2 to a device nobody ever verified).
        // Never allowed to override an already-positive base either way: doing that
        // unconditionally (e.g. via a "projected" self-entry merged into the evidence
        // before resolving) would let a tool with a higher maxAcr than the CURRENT session
        // actually proved (that same enroll-device, enrolled from a loa1-only session)
        // silently escalate past what was ever really established - exactly the
        // self-escalation ADR-5 exists to prevent.
        val environmentAcr = authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId))
        val enrolledUnderAcr = if (environmentAcr == AcrLevel.NONE) AcrLevels.DEFAULT_REQUIRED_ACR else environmentAcr
        // Recorded AFTER the level is known, because an anchor write is priced against it
        // (AnchorRule.acrFloor) and the anchor row remembers it. Safe to order this way:
        // resolveAcr computes purely from the evidence - its `account` argument is not read - so
        // the figure is the same whether the claims have landed yet or not.
        accountService.recordClaims(
            accountId,
            enrolled.claims,
            // The same capped figure the credential itself is stamped with (ADR-5).
            provenAcr = enrolledUnderAcr,
            authMethodId = methodInstanceId
        )
        // Demo-only transparency for the ADR-5 cap above: this tool's own maxAcr promises
        // more than the session had actually established, so the credential just created is
        // quietly weaker than its catalog entry suggests - visible here once, at the moment
        // it happens, rather than only discoverable later as a confusing STEP_UP/CONFIRM_
        // PEER_LOGIN rejection with no obvious cause (docs/04-orchestrierung.md #8).
        val demoNotice =
            if (AcrLevel.rank(enrolledUnderAcr) < AcrLevel.rank(action.tool.maxAcr)) {
                mapOf(
                    "enrolledUnderAcrCapped" to mapOf(
                        "toolId" to action.tool.toolId,
                        "enrolledUnderAcr" to enrolledUnderAcr,
                        "toolMaxAcr" to action.tool.maxAcr
                    )
                )
            } else null
        accountService.addAuthenticationMethod(
            accountId,
            action.tool.method,
            enrolled.enrollmentRef,
            enrolledUnderAcr = enrolledUnderAcr.value,
            details = enrolled.auditDetails.orEmpty().minus("label") + mapOf(
                "enrolledUnderAmr" to evidence.currentAmr,
                "channel" to channel.channel?.name
            ),
            allowsMultipleInstances = action.tool.allowsMultipleInstances,
            label = label,
            instanceId = methodInstanceId
        )
        linkDeviceIfIntentImplies(journey, channel, accountId)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, enrolled, enrolled.achievedAcr)
        return demoNotice
    }

    /**
     * Whether the tool that just proved a credential may NAME the account it proved, derived here
     * rather than taken from the caller (see [Action.AcceptProof]'s own doc for the flag this
     * replaced):
     *
     * - Only [MethodRole.LOOKUP_AUTH] may name one at all. That role's entire contract is
     *   "resolves the account itself from a submitted identifier"; every other role proves a
     *   credential OF an account the channel already knows, so a named account from one of those
     *   is a tool/handler bug, not a login path.
     * - Even for a lookup tool, a named account must AGREE with one this journey/channel already
     *   holds. Nothing legitimately re-resolves a different account mid-journey - that is the
     *   silent switch this whole class of bug is made of - so a disagreement is a `409`.
     */
    private fun accountOfProof(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof): Long {
        val inHand = journey.accountId ?: channel.accountId
        val named = action.outcome.accountId?.takeIf { action.tool.role == MethodRole.LOOKUP_AUTH }
        if (named != null && inHand != null && named != inHand) {
            throw IdentityConflictException(Text("Der Nachweis gehoert zu einem anderen Konto als dieser Sitzung"))
        }
        return checkNotNull(named ?: inHand) { "Authenticated without a known account" }
    }

    private fun performAcceptProof(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof) {
        val authenticated = action.outcome
        val accountId = accountOfProof(journey, channel, action)
        bindAccount(journey, channel, accountId)
        linkDeviceIfIntentImplies(journey, channel, accountId)
        val used = checkNotNull(accountService.findActiveMethod(accountId, action.tool.method)) {
            "No active method '${action.tool.method}' for account $accountId"
        }
        val effectiveAcr = AcrLevel.min(authenticated.achievedAcr, used.enrolledUnderAcr?.let(AcrLevel::of))
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, authenticated, effectiveAcr)
    }

    /**
     * The device link that follows from SUCCEEDING, as opposed to the one a user explicitly asked
     * for ([performLinkDevice]): whether it happens is the journey intent's own property
     * ([AuthIntent.bindsDeviceImplicitly]), never a per-Action flag a strategy filled in.
     */
    private fun linkDeviceIfIntentImplies(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        val intent = checkNotNull(journey.intent) { "Journey without an intent" }
        if (!intent.bindsDeviceImplicitly) return
        // Never REBIND implicitly. Succeeding at an ordinary flow is consent to be recognized by
        // THIS account next time; it is not consent to take the device away from another one,
        // which additionally revokes that account's device credentials (see [linkDeviceTo]).
        // Rebinding is destructive, so it only ever happens down the explicit route, after the
        // user answered a prompt that says so (RegisterState/LookupLoginState.ConfirmDeviceRebind).
        //
        // Checked here rather than left to each strategy to ask first: RegisterStrategy does ask,
        // RegisterEnrollFirstStrategy has no such state at all - the same "every caller must
        // remember" shape as the flags this replaced. A strategy that WANTS the rebind still gets
        // it, by asking and then naming Action.LinkDevice.
        val bindingKeyRef = channel.bindingKeyRef
        if (bindingKeyRef != null) {
            val linkedTo = sessionManagementService.findLinkedAccountId(bindingKeyRef)
            if (linkedTo != null && linkedTo != accountId) return
        }
        linkDeviceTo(channel, accountId)
    }

    /** The account this session currently holds - never one a strategy stored in its state earlier (see [Action.LinkDevice]). */
    private fun performLinkDevice(journey: AuthJourney, channel: ChannelSession) {
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "LinkDevice without a known account" }
        linkDeviceTo(channel, accountId)
    }

    /**
     * THE one way a device becomes linked to an account - both the implicit route
     * ([linkDeviceIfIntentImplies]) and the explicit one ([performLinkDevice], after the user
     * agreed) go through here, so the revocation below can never be skipped by picking a
     * different Action - the same rebind must not revoke or not revoke depending on which
     * strategy's Action carried it there.
     *
     * Reaching here with a device currently linked ELSEWHERE therefore means a deliberate,
     * user-confirmed rebind: the implicit route refuses that case before calling in (see there).
     *
     * KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
     * suppressed rather than left to a null bindingKeyRef, so a KEYCLOAK channel never
     * accumulates dead DeviceAccountLink rows even if a strategy ever asked for this.
     */
    private fun linkDeviceTo(channel: ChannelSession, accountId: Long) {
        if (channel.channel != ChannelType.APP) return
        val bindingKeyRef = checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" }
        val previousAccountId = sessionManagementService.findLinkedAccountId(bindingKeyRef)
        sessionManagementService.linkDeviceToAccount(bindingKeyRef, accountId)
        // A device is only ever actively bound to one account at a time - once rebound
        // (docs/04-orchestrierung.md #2, RegisterState.ConfirmDeviceRebind), the previous
        // account's own device-bound credential(s) for this exact physical key must not
        // keep working (docs/09-dpop.md); Phase 1's matching guard already hides them, this
        // additionally removes them outright.
        if (previousAccountId != null && previousAccountId != accountId) {
            credentialsLivingOn(bindingKeyRef, previousAccountId).forEach {
                accountDeletionService.revokeMethod(previousAccountId, checkNotNull(it.id) { "Active method without an id" })
            }
        }
    }

    /**
     * The active methods of [accountId] whose credential physically lives on the key
     * [bindingKeyRef] - i.e. exactly those that stop being usable when that key moves to another
     * account. A method with no [ToolDescriptor.keyBinding] is not tied to a key at all and can
     * never be in this list; one that has a binding answers, per instance, whether THIS one is the
     * one on THIS key - a question only the owning module can answer.
     *
     * The descriptor is resolved by `(method, IDENTIFIED_AUTH)`, the pair that names one concrete
     * procedure - never by method name alone, which would match this method's enrollment tool just
     * as well and answer from the wrong declaration.
     */
    private fun credentialsLivingOn(bindingKeyRef: String, accountId: Long): List<AuthMethodView> =
        accountService.findAccount(accountId)?.activeAuthenticationMethods.orEmpty().filter { method ->
            val binding = toolRegistry.descriptors()
                .firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == method.method }
                ?.keyBinding
            binding != null && binding.livesOn(method.details, bindingKeyRef)
        }

    /**
     * The account is taken from the freshly derived context and NOTHING else - the same one the
     * permission check below runs against, so no second source can name a different account and
     * turn this into "checked on A, deleted B".
     */
    private fun performDeleteAccount(journey: AuthJourney, channel: ChannelSession) {
        // Independent re-check against freshly derived context, not the strategy's own
        // state - same reasoning as the self-lockout check before Action.RevokeAuthMethod.
        val freshCtx = contextFactory.contextFor(journey, channel)
        val account = checkNotNull(freshCtx.account) { "DeleteAccount without a resolved account" }
        val requiredAcr = Action.DeleteAccount.requiredAcr(account)
        check(authPolicy.isSatisfied(freshCtx.evidence, requiredAcr, account)) {
            "${journey.intent} decided Action.DeleteAccount without satisfying $requiredAcr"
        }
        accountDeletionService.deleteAccount(account.accountId)
    }

    /**
     * The one action a strategy may ask for that is not a tool run. Guarded against self-lockout:
     * rejected if the account could no longer reach its own channel's floor afterwards.
     */
    private fun removeMethod(journey: AuthJourney, channel: ChannelSession, methodInstanceId: String) {
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "Remove without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${accountId}")
        val target = account.authenticationMethods.firstOrNull { it.active && it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound(Text("No such active method for this account"), "methodInstanceId=${methodInstanceId}")

        val dependents = dependentsOf(account, target)
        val falling = (listOf(target) + dependents).map { it.id }.toSet()

        val afterRemoval = account.copy(
            authenticationMethods = account.authenticationMethods.map {
                if (it.id in falling) it.copy(active = false) else it
            }
        )
        if (authPolicy.reachability(afterRemoval, contextFactory.acrFloorOf(channel)) !is Reachability.Reachable) {
            val alsoFalling = dependents.map { it.method }.distinct()
            throw OrchestratorException.invalidState(
                if (alsoFalling.isEmpty()) Text("Deaktivieren von '{method}' wuerde das Mindestniveau dieses Kanals unterschreiten", "method" to target.method)
                else Text("Deaktivieren von '{method}' (zusammen mit {alsoFalling}) wuerde das Mindestniveau dieses Kanals unterschreiten", "method" to target.method, "alsoFalling" to alsoFalling.joinToString(", "))
            )
        }
        // revokeMethod, not the bare deactivate: a user who removes a method expects the
        // credential itself gone, not merely unusable. It deletes the owning module's row
        // (EnrollmentCleanup) and THEN deactivates the instance - the deactivated row stays, so
        // account deletion still walks every ref it ever pointed at. Device rebinding already
        // took this path (docs/09-dpop.md); the user-facing removal used to stop at the flag and
        // left the phone number / password hash behind until the whole account went.
        //
        // Dependents first, then the method that carried them: the reverse order would leave a
        // window in which a dependent credential exists without what it depends on.
        dependents.forEach { accountDeletionService.revokeMethod(accountId, checkNotNull(it.id)) }
        accountDeletionService.revokeMethod(accountId, methodInstanceId)
    }

    /**
     * Withdraws one account attribute and takes with it everything that required it - which is
     * how a confirmed address can finally be lost at all (`AccountService.retractAttribute`).
     *
     * The consequence is not listed anywhere: `enroll-password` requires a proven EMAIL and
     * `enroll-kobil` requires the password's own claim, so retracting the address removes both,
     * found by the same fixpoint a method revocation uses. The floor check runs on the full
     * projection first, so this refuses rather than leaving the channel below its own minimum.
     */
    private fun performRetractAttribute(journey: AuthJourney, channel: ChannelSession, attributeType: AttributeType) {
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "Retract without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${accountId}")

        // Refuse rather than treat it as a no-op: without this, withdrawing something the account
        // never established would still run the cascade below and revoke whatever names that
        // attribute in its `requires` - a destructive act triggered by a request that withdraws
        // nothing at all.
        if (attributeType !in account.establishedClaims) {
            throw OrchestratorException.notFound(Text("'{attributeType}' ist fuer dieses Konto nicht bestaetigt", "attributeType" to attributeType.wireName))
        }

        val falling = dependentsOfLostClaims(account, lost = setOf(attributeType), falling = emptyList())
        val fallingIds = falling.mapNotNull { it.id }.toSet()
        val afterRetraction = account.copy(
            authenticationMethods = account.authenticationMethods.map {
                if (it.id in fallingIds) it.copy(active = false) else it
            }
        )
        if (authPolicy.reachability(afterRetraction, contextFactory.acrFloorOf(channel)) !is Reachability.Reachable) {
            val alsoFalling = falling.map { it.method }.distinct()
            throw OrchestratorException.invalidState(
                if (alsoFalling.isEmpty()) Text("Zuruecknehmen von '{attributeType}' wuerde das Mindestniveau dieses Kanals unterschreiten", "attributeType" to attributeType.wireName)
                else Text("Zuruecknehmen von '{attributeType}' (zusammen mit {alsoFalling}) wuerde das Mindestniveau dieses Kanals unterschreiten", "attributeType" to attributeType.wireName, "alsoFalling" to alsoFalling.joinToString(", "))
            )
        }

        falling.forEach { accountDeletionService.revokeMethod(accountId, checkNotNull(it.id)) }
        accountService.retractAttribute(accountId, attributeType, RetractionAnchor.ACCOUNT_MANAGEMENT, reason = "attribute withdrawn")
    }

    /**
     * The active credentials that cannot outlive [target] - transitively.
     *
     * Nobody declares this dependency as such: it is read off the `requires` gates that are
     * already there. Revoking a method retracts the MethodModule claims it asserted
     * (`AccountService.retractClaimsOf`), and any method whose `requires` named one of those
     * loses its own precondition - so it cannot stand either, and its claims are then gone in
     * turn. Hence the fixpoint rather than a single pass, even though the catalog today happens
     * to hold only chains of length two.
     *
     * A requirement on a claim that no method instance asserted needs its own trigger, and has
     * one: `enroll-password` requires a confirmed EMAIL, but `confirm-email` is an ATTESTATION -
     * it writes its claim with no `authMethodId` (`performAdoptAttestation`), and EMAIL belongs to
     * the account itself (`AttributeAuthority.Local`) besides, so no method revocation can ever
     * reach it. That is why
     * [performRetractAttribute] exists and calls this same fixpoint: losing an address takes the
     * password and the email login with it (ADR-24).
     *
     * This is where `requires` stops being merely an offering gate and becomes a standing
     * precondition (see [ToolDescriptor.requires]). A claim that survives the revocation - an
     * account-owned anchor like EMAIL, or one another active method also asserts - keeps its
     * dependents alive, which is why [stillClaimed] is subtracted rather than assumed empty.
     */
    private fun dependentsOf(account: AccountProfile, target: AuthMethodView): List<AuthMethodView> {
        val lost = claimedTypes(account, listOf(target)) -
            claimedTypes(account, account.activeAuthenticationMethods.filter { it.id != target.id })
        return dependentsOfLostClaims(account, lost, falling = listOf(target))
    }

    private fun claimedTypes(account: AccountProfile, instances: List<AuthMethodView>): Set<AttributeType> =
        instances.flatMap { accountService.claimedTypesOf(account.accountId, checkNotNull(it.id)) }.toSet()

    /**
     * The fixpoint itself, shared by both causes of a claim going away: a revoked credential that
     * asserted it, or an attribute withdrawn outright. [falling] is what is already known to go
     * (empty for a retraction, the revoked instance for a revocation); the result adds every
     * active credential whose `requires` just stopped being satisfied, and keeps going while each
     * new casualty takes its own claims with it.
     */
    private fun dependentsOfLostClaims(
        account: AccountProfile,
        lost: Set<AttributeType>,
        falling: List<AuthMethodView>
    ): List<AuthMethodView> {
        val casualties = falling.toMutableList()
        var lostSoFar = lost
        while (true) {
            val fallingIds = casualties.mapNotNull { it.id }.toSet()
            val standing = account.activeAuthenticationMethods.filter { it.id !in fallingIds }
            val next = standing.filter { instance ->
                toolRegistry.descriptors()
                    .filter { it.method == instance.method && it.role.category == ToolCategory.ENROLL }
                    .any { descriptor -> descriptor.requires.any { it.attributeType in lostSoFar } }
            }
            if (next.isEmpty()) return casualties.drop(falling.size)
            casualties += next
            // A casualty takes its own MethodModule claims with it - unless something still
            // standing asserts the same type, which is why this is recomputed rather than unioned.
            val stillStanding = account.activeAuthenticationMethods
                .filter { it.id !in casualties.mapNotNull { c -> c.id }.toSet() }
            lostSoFar = lostSoFar + (claimedTypes(account, casualties) - claimedTypes(account, stillStanding))
        }
    }

    /**
     * Both channel types get an [com.example.dpop.orchestrator.session.AuthEvidence] here - the
     * shared evidence trail, regardless of facade. Only the APP channel additionally gets an
     * [com.example.dpop.orchestrator.session.AuthContext] (docs/ideen/web-keycloak-kanal.md #6):
     * token issuance is App-exclusive, KEYCLOAK never calls `getToken`/`getIdClaims` - the one
     * deliberate, documented channel-type branch in this method.
     */
    private fun bindAccount(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        journey.accountId = accountId
        channel.accountId = accountId
        if (channel.authEvidenceId == null) {
            // Fresh login: start a new evidence trail rather than reuse a stale one.
            val evidenceId = checkNotNull(authEvidenceService.createForAccount(accountId).authEvidenceId)
            channel.authEvidenceId = evidenceId
            if (channel.channel == ChannelType.APP) {
                channel.authContextId = authContextService.createForAccount(accountId, evidenceId).authContextId
            }
        }
        sessionManagementService.updateChannelSession(channel)
        journeyRepository.save(journey)
    }
}

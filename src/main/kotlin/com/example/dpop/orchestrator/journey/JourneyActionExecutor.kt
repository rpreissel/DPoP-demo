package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.domain.journey.Action
import com.example.dpop.orchestrator.domain.journey.proofLevel
import com.example.dpop.orchestrator.domain.journey.linksDeviceImplicitly
import com.example.dpop.orchestrator.domain.journey.levelToWriteUnder
import com.example.dpop.orchestrator.domain.journey.credentialsLivingOn
import com.example.dpop.orchestrator.domain.journey.checkCorrelation
import com.example.dpop.orchestrator.domain.journey.checkAttestationMove
import com.example.dpop.orchestrator.domain.journey.accountOfProof
import com.example.dpop.orchestrator.domain.journey.MethodDependencies
import com.example.dpop.orchestrator.domain.journey.IdentificationTarget
import com.example.dpop.orchestrator.domain.journey.AccountMerge
import com.example.dpop.orchestrator.domain.journey.IntentStrategy
import com.example.dpop.orchestrator.domain.journey.JourneyEvent
import com.example.dpop.orchestrator.domain.journey.Transition
import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.account.RetractionAnchor
import com.example.dpop.orchestrator.domain.OrchestratorException
import com.example.dpop.orchestrator.domain.policy.AuthEvidence
import com.example.dpop.orchestrator.domain.policy.AuthPolicy
import com.example.dpop.orchestrator.domain.policy.Reachability
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.toCoreEvidence
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_api.IdentityResolver
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.assertClaimsCovered
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.example.dpop.orchestrator.domain.AuthIntent

/**
 * The acting phase of a transition: the [Action]s a [Transition.Perform] can carry, actually
 * executed. Separate from [JourneyService] along the machine's own phase boundary - that service
 * DECIDES and ROUTES (which strategy, which transition, where next), this class WRITES (accounts,
 * claims, credentials, device links, revocations).
 *
 * The split is the same one [IntentStrategy] already makes one level up, continued: a strategy
 * decides but never acts, and the service that drives it does not mix the driving with the
 * acting either. Every side effect a journey can have is reachable from exactly one file, so
 * "no intent can forget the ACR cap or skip the change log" stays inspectable.
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
    /**
     * Whether [personId] is the person the account in hand already had attested - the question a
     * CORRELATION tool asks before it reports (`ToolEndpoint.matchesAttestedIdentity`, review
     * 2026-09 S-6), answered by the same rule [performRecordIdentification] enforces afterwards.
     * Asked here because only this class may consult [IdentityResolver]; answering it acts on
     * nothing.
     */
    fun matchesAttestedIdentity(journey: AuthJourney, channel: ChannelSession, personId: String): Boolean {
        val inHand = channel.accountId ?: return false
        return identityResolver.attestedIdentityMatches(inHand, personId)
    }

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
     * Central identity resolution (docs/archiv/claims-modell-und-vertrauensanker.md,
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
        val inHand = channel.accountId
        // A correlation step proves nothing about the subject on its own (ADR-18): see checkCorrelation.
        if (action.tool.role == MethodRole.CORRELATION) {
            val correlatingAccount = checkNotNull(inHand) { "Correlation without a known account under ${journey.intent}" }
            checkCorrelation(loadAccount(correlatingAccount), action.tool.toolId, action.outcome.personId) { personId ->
                identityResolver.attestedIdentityMatches(correlatingAccount, personId)
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
            // Nothing resolved - the attested subject has no account yet: IdentificationTarget decides.
            Resolution.Unresolved -> {
                val inHandAccount = inHand?.let { accountService.findAccount(it) }
                val target = IdentificationTarget.forUnresolved(inHandAccount) {
                    identityResolver.attestationFits(checkNotNull(inHandAccount).accountId, action.outcome.claims.toSet())
                }
                when (target) {
                    IdentificationTarget.NewAccount -> accountService.createUnidentifiedAccount().accountId
                    is IdentificationTarget.AccountInHand -> target.accountId
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

    /** Which account a confirmed identification actually writes to - see [AccountMerge] (ADR-20). */
    private fun accountOf(journey: AuthJourney, channel: ChannelSession, inHand: Long, resolved: Long?): Long {
        if (resolved == null || resolved == inHand) return inHand
        return when (val merge = AccountMerge.decide(loadAccount(inHand)) { loadAccount(resolved) }) {
            is AccountMerge.MoveInto -> {
                rebindAccount(journey, channel, from = merge.from, to = merge.into)
                accountService.absorbProvisionalAccount(merge.from, merge.into)
                merge.into
            }
            is AccountMerge.AbsorbResolved -> {
                accountService.absorbProvisionalAccount(merge.resolved, merge.into)
                merge.into
            }
        }
    }

    private fun loadAccount(accountId: Long): AccountProfile =
        accountService.findAccount(accountId) ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=$accountId")

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
     * confirming an address never makes email an authentication method by accident; no device
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
        val inHand = channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Attested without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        val accountId = accountOfAttestation(journey, channel, inHand, action, coreEvidence)
        // The same capped figure an enrollment is stamped with - an anchor write is priced by what
        // the session actually proved (AnchorRule.acrFloor), never by the tool's ceiling.
        val provenAcr = levelToWriteUnder(authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId)))
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = provenAcr)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    /** An attested value that resolves to ANOTHER account - see [checkAttestationMove]. */
    private fun accountOfAttestation(
        journey: AuthJourney,
        channel: ChannelSession,
        inHand: Long,
        action: Action.AdoptAttestation,
        evidence: AuthEvidence
    ): Long {
        val resolved = (identityResolver.resolve(action.outcome.claims.toSet()) as? Resolution.ExistingAccount)?.accountId
        if (resolved == null || resolved == inHand) return inHand
        checkAttestationMove(evidence, accountService.findAccount(resolved)?.personId) { personId ->
            identityResolver.attestedIdentityMatches(inHand, personId)
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
        val accountId = channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Enrolled without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        val label = enrolled.label
        // Generated here rather than by addAuthenticationMethod below, because the claims are
        // recorded FIRST (the acr computation between the two deliberately reads the account
        // including them) and each has to name the instance that established it, so revoking the
        // method can retract exactly that later (ADR-12).
        val methodInstanceId = UUID.randomUUID()
        // Every claim this enrollment asserted lands in the account's identity log
        // (AccountService.recordClaims, a few lines below); an EMAIL claim additionally
        // consolidates its anchor. Done here, before this
        // method returns, so the very next context rebuild (JourneyEvent.ActionCompleted) already
        // sees it.
        // What the session had established BEFORE this completion (recordToolCompletion for this one
        // has not run yet) - see levelToWriteUnder (ADR-5).
        val enrolledUnderAcr = levelToWriteUnder(authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId)))
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
            details = enrolled.instanceDetails,
            enrolledUnderAmr = evidence.currentAmr,
            channel = channel.channel?.name,
            allowsMultipleInstances = action.tool.allowsMultipleInstances,
            label = label,
            instanceId = methodInstanceId
        )
        linkDeviceIfIntentImplies(journey, channel, accountId)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, enrolled, enrolled.achievedAcr)
        return demoNotice
    }

    private fun performAcceptProof(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof) {
        val authenticated = action.outcome
        val accountId = accountOfProof(action.tool.role, action.outcome.accountId, channel.accountId)
        bindAccount(journey, channel, accountId)
        linkDeviceIfIntentImplies(journey, channel, accountId)
        // Capped by the instance that was used - see proofLevel (ADR-5).
        val effectiveAcr = proofLevel(
            accountService.findActiveMethods(accountId, action.tool.method), action.tool.keyBinding, channel.bindingKeyRef, authenticated.achievedAcr
        )
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, authenticated, effectiveAcr)
    }

    /**
     * The device link that follows from SUCCEEDING, as opposed to the one a user explicitly asked
     * for ([performLinkDevice]): whether it happens is the journey intent's own property
     * ([AuthIntent.bindsDeviceImplicitly]), never a per-Action flag a strategy filled in.
     */
    private fun linkDeviceIfIntentImplies(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        val intent = checkNotNull(journey.intent) { "Journey without an intent" }
        val linkedTo = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) }
        if (linksDeviceImplicitly(intent, linkedTo, accountId)) linkDeviceTo(channel, accountId)
    }

    /** The account this session currently holds - never one a strategy stored in its state earlier (see [Action.LinkDevice]). */
    private fun performLinkDevice(journey: AuthJourney, channel: ChannelSession) {
        val accountId = checkNotNull(channel.accountId) { "LinkDevice without a known account" }
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
            credentialsLivingOn(accountService.findAccount(previousAccountId), bindingKeyRef, toolRegistry).forEach {
                accountDeletionService.revokeMethod(previousAccountId, checkNotNull(it.id) { "Active method without an id" })
            }
        }
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
        val accountId = checkNotNull(channel.accountId) { "Remove without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${accountId}")
        val target = account.authenticationMethods.firstOrNull { it.active && it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound(Text("No such active method for this account"), "methodInstanceId=${methodInstanceId}")

        val dependencies = methodDependencies(account)
        val dependents = dependencies.dependentsOf(target)
        val afterRemoval = dependencies.without(listOf(target) + dependents)
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
        // account deletion still walks every ref it ever pointed at. Device rebinding takes the
        // same path (docs/09-dpop.md); stopping at the flag would leave the phone number /
        // password hash behind until the whole account went.
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
        val accountId = checkNotNull(channel.accountId) { "Retract without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${accountId}")

        // Refuse rather than treat it as a no-op: without this, withdrawing something the account
        // never established would still run the cascade below and revoke whatever names that
        // attribute in its `requires` - a destructive act triggered by a request that withdraws
        // nothing at all.
        if (attributeType !in account.establishedClaims) {
            throw OrchestratorException.notFound(Text("'{attributeType}' ist fuer dieses Konto nicht bestaetigt", "attributeType" to attributeType.wireName))
        }

        val dependencies = methodDependencies(account)
        val falling = dependencies.dependentsOfLostClaims(lost = setOf(attributeType), falling = emptyList())
        val afterRetraction = dependencies.without(falling)
        if (authPolicy.reachability(afterRetraction, contextFactory.acrFloorOf(channel)) !is Reachability.Reachable) {
            val alsoFalling = falling.map { it.method }.distinct()
            throw OrchestratorException.invalidState(
                if (alsoFalling.isEmpty()) Text("Zuruecknehmen von '{attributeType}' wuerde das Mindestniveau dieses Kanals unterschreiten", "attributeType" to attributeType.wireName)
                else Text("Zuruecknehmen von '{attributeType}' (zusammen mit {alsoFalling}) wuerde das Mindestniveau dieses Kanals unterschreiten", "attributeType" to attributeType.wireName, "alsoFalling" to alsoFalling.joinToString(", "))
            )
        }

        falling.forEach { accountDeletionService.revokeMethod(accountId, checkNotNull(it.id)) }
        accountService.retractAttribute(accountId, attributeType, RetractionAnchor.ACCOUNT_HOLDER, reason = "attribute withdrawn")
    }

    /** What falls with a credential or an attribute - see [MethodDependencies]; the claim log answers what each instance asserted. */
    private fun methodDependencies(account: AccountProfile) =
        MethodDependencies(account, toolRegistry) { accountService.claimedTypesOf(account.accountId, checkNotNull(it.id)) }

    /**
     * Both channel types get an [com.example.dpop.orchestrator.session.EvidenceTrail] here - the
     * shared evidence trail, regardless of facade. Only the APP channel additionally gets an
     * [com.example.dpop.orchestrator.session.AuthContext] (docs/05-api.md, "AccessToken"):
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

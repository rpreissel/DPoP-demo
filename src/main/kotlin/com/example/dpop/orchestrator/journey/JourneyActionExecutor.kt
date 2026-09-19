package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.session.AcrLevels
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
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.assertClaimsCovered
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
     * [com.example.dpop.tool_spi.DEMO_DATA_KEY]), or `null` when this action has none. Currently
     * only [Action.AdoptCredential] ever returns one - see [performAdoptCredential] for why.
     */
    fun perform(journey: AuthJourney, channel: ChannelSession, action: Action): Map<String, Any?>? {
        var demoNotice: Map<String, Any?>? = null
        when (action) {
            is Action.AdoptIdentity -> performAdoptIdentity(journey, channel, action)
            is Action.ConfirmIdentity -> performConfirmIdentity(journey, channel, action)
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
            is Action.Remove -> removeMethod(journey, channel, action.methodInstanceId)
            is Action.LinkDevice -> performLinkDevice(channel, action)
            is Action.DeleteAccount -> performDeleteAccount(journey, channel, action)
        }
        return demoNotice
    }

    /**
     * Central identity resolution (docs/ideen/claims-modell-und-vertrauensanker.md,
     * "Identitaetsauflösung & Matching"): the account module owns the matching policy, this
     * service only governs the consequences.
     */
    private fun performAdoptIdentity(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptIdentity) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        val resolution = identityResolver.resolve(action.outcome.claims.toSet())
        val accountId = when (resolution) {
            is Resolution.ExistingAccount -> resolution.accountId
            // The account and its claims share this journey transaction, including rollback.
            Resolution.NewInteressent -> accountService.createUnidentifiedAccount().accountId
        }
        bindAccount(journey, channel, accountId)
        // An identification's own achieved level IS what this session proved about the identity -
        // the figure AnchorRule.acrFloor prices the PERSON_ID anchor against.
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = action.outcome.achievedAcr ?: AcrLevel.NONE)
        journeyRecorder.recordIdentification(journey, channel, action.tool, action.outcome)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    private fun performConfirmIdentity(journey: AuthJourney, channel: ChannelSession, action: Action.ConfirmIdentity) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) {
            "Identified without a known account under ${journey.intent}"
        }
        when (val resolution = identityResolver.resolve(action.outcome.claims.toSet())) {
            is Resolution.ExistingAccount ->
                if (resolution.accountId != accountId) {
                    throw IdentityConflictException("Identification claims resolve to a different account")
                }
            // A known account may still be an unbound enrollment-first account. In that case
            // resolution has no existing anchor to return yet; the shared recordClaims call
            // below performs the first immutable PERSON_ID binding.
            Resolution.NewInteressent -> Unit
        }
        // A MethodRole.CORRELATION step (ident-kvnr, ADR-18) proves nothing about the subject
        // itself - it only turns a typed number into a register-vouched PERSON_ID, which is
        // exactly what that role declares. An IDENTIFICATION tool may bind on its own strength
        // (ident-fsc's mailed code IS possession of something sent to that very person); a
        // correlation step may not: its whole security argument is that the register's person
        // matches what THIS account already had attested. Without the check, attesting yourself
        // and then typing a stranger's number would bind their anchor here, whenever that
        // stranger has no account of their own yet.
        if (action.tool.role == MethodRole.CORRELATION) {
            action.outcome.personId?.let { claimedPersonId ->
                if (accountService.findAccount(accountId)?.personId == null &&
                    !identityResolver.attestedIdentityMatches(accountId, claimedPersonId)
                ) {
                    throw IdentityConflictException("Die Versichertennummer gehoert nicht zu der nachgewiesenen Identitaet")
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
        val accountId = journey.accountId ?: channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Attested without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        // The same capped figure an enrollment is stamped with - an anchor write is priced by what
        // the session actually proved (AnchorRule.acrFloor), never by the tool's ceiling.
        val environmentAcr = authPolicy.resolveAcr(evidence.toCoreEvidence(), accountService.findAccount(accountId))
        val provenAcr = if (environmentAcr == AcrLevel.NONE) AcrLevels.DEFAULT_REQUIRED_ACR else environmentAcr
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = provenAcr)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
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
        // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
        // suppressed, not just incidentally skipped by a null bindingKeyRef.
        if (action.bindDevice && channel.channel == ChannelSession.Channel.APP) {
            sessionManagementService.linkDeviceToAccount(
                checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" },
                accountId
            )
        }
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, enrolled, enrolled.achievedAcr)
        return demoNotice
    }

    private fun performAcceptProof(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof) {
        val authenticated = action.outcome
        val resolved = authenticated.accountId.takeIf { action.useOutcomeAccount }
        val accountId = checkNotNull(resolved ?: journey.accountId ?: channel.accountId) {
            "Authenticated without a known account"
        }
        bindAccount(journey, channel, accountId)
        // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
        // suppressed, not just incidentally skipped by a null bindingKeyRef.
        if (action.bindDevice && channel.channel == ChannelSession.Channel.APP) {
            sessionManagementService.linkDeviceToAccount(
                checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" },
                accountId
            )
        }
        val used = checkNotNull(accountService.findActiveMethod(accountId, action.tool.method)) {
            "No active method '${action.tool.method}' for account $accountId"
        }
        val effectiveAcr = AcrLevel.min(authenticated.achievedAcr, used.enrolledUnderAcr?.let(AcrLevel::of))
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, authenticated, effectiveAcr)
    }

    private fun performLinkDevice(channel: ChannelSession, action: Action.LinkDevice) {
        // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
        // suppressed rather than left to a null bindingKeyRef, so a KEYCLOAK channel never
        // accumulates dead DeviceAccountLink rows even if a strategy ever offered this.
        if (channel.channel == ChannelSession.Channel.APP) {
            val bindingKeyRef = checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" }
            val previousAccountId = sessionManagementService.findLinkedAccountId(bindingKeyRef)
            sessionManagementService.linkDeviceToAccount(bindingKeyRef, action.accountId)
            // A device is only ever actively bound to one account at a time - once rebound
            // (docs/04-orchestrierung.md #2, RegisterState.ConfirmDeviceRebind), the previous
            // account's own device-bound credential(s) for this exact physical key must not
            // keep working (docs/09-dpop.md); Phase 1's matching guard already hides them, this
            // additionally removes them outright.
            if (previousAccountId != null && previousAccountId != action.accountId) {
                accountService.findAccount(previousAccountId)?.activeAuthenticationMethods
                    ?.filter { m ->
                        val descriptor = toolRegistry.descriptors().firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == m.method }
                        descriptor != null && descriptor.allowsMultipleInstances && descriptor.matchesCaller(m.details, bindingKeyRef)
                    }
                    ?.forEach { accountDeletionService.revokeMethod(previousAccountId, checkNotNull(it.id) { "Active method without an id" }) }
            }
        }
    }

    private fun performDeleteAccount(journey: AuthJourney, channel: ChannelSession, action: Action.DeleteAccount) {
        // Independent re-check against freshly derived context, not the strategy's own
        // state - same reasoning as the self-lockout check before Action.Remove.
        val freshCtx = contextFactory.contextFor(journey, channel)
        val account = checkNotNull(freshCtx.account) { "DeleteAccount without a resolved account" }
        val requiredAcr = Action.DeleteAccount.requiredAcr(account)
        check(authPolicy.isSatisfied(freshCtx.evidence, requiredAcr, account)) {
            "${journey.intent} decided Action.DeleteAccount without satisfying $requiredAcr"
        }
        accountDeletionService.deleteAccount(action.accountId)
    }

    /**
     * The one action a strategy may ask for that is not a tool run. Guarded against self-lockout:
     * rejected if the account could no longer reach its own channel's floor afterwards.
     */
    private fun removeMethod(journey: AuthJourney, channel: ChannelSession, methodInstanceId: String) {
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "Remove without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone("Account not found: $accountId")
        val target = account.authenticationMethods.firstOrNull { it.active && it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound("No active method '$methodInstanceId' for this account")

        val afterRemoval = account.copy(
            authenticationMethods = account.authenticationMethods.map {
                if (it.id == methodInstanceId) it.copy(active = false) else it
            }
        )
        if (authPolicy.reachability(afterRemoval, contextFactory.acrFloorOf(channel)) !is Reachability.Reachable) {
            throw OrchestratorException.invalidState(
                "Deaktivieren von '${target.method}' wuerde das Mindestniveau dieses Kanals unterschreiten"
            )
        }
        // revokeMethod, not the bare deactivate: a user who removes a method expects the
        // credential itself gone, not merely unusable. It deletes the owning module's row
        // (EnrollmentCleanup) and THEN deactivates the instance - the deactivated row stays, so
        // account deletion still walks every ref it ever pointed at. Device rebinding already
        // took this path (docs/09-dpop.md); the user-facing removal used to stop at the flag and
        // left the phone number / password hash behind until the whole account went.
        accountDeletionService.revokeMethod(accountId, methodInstanceId)
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
            if (channel.channel == ChannelSession.Channel.APP) {
                channel.authContextId = authContextService.createForAccount(accountId, evidenceId).authContextId
            }
        }
        sessionManagementService.updateChannelSession(channel)
        journeyRepository.save(journey)
    }
}

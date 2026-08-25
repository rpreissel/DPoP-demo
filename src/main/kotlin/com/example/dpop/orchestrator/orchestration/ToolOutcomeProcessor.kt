package com.example.dpop.orchestrator.orchestration

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.ManageMethodsProcessSession
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.RegistrationProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.StepUpProcessSession
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Turns a completed tool into account/AuthContext side effects and the next process step
 * (docs/04-orchestrierung.md #1). The single exhaustive `when` over Completed's variants is
 * intentional: side effects hang off the tool's CATEGORY, never off its concrete toolId.
 */
@Service
@Transactional
class ToolOutcomeProcessor(
    private val toolRegistry: ToolHandlerRegistry,
    private val accountService: AccountService,
    private val authContextService: AuthContextService,
    private val sessionManagementService: SessionManagementService,
    private val authPolicy: AuthPolicy
) {
    data class Result(val next: Next, val stepData: Map<String, Any?>? = null)

    fun process(
        toolId: String,
        outcome: ToolOutcome.Completed,
        processSession: ProcessSession,
        channelSession: ChannelSession
    ): Result {
        val method = toolRegistry.descriptorOf(toolId).method

        val effectiveAcr: String? = when (outcome) {
            is ToolOutcome.Completed.Identified -> handleIdentified(outcome, processSession, channelSession, method)
            is ToolOutcome.Completed.Enrolled -> handleEnrolled(outcome, processSession, channelSession, method)
            is ToolOutcome.Completed.Authenticated -> handleAuthenticated(outcome, processSession, channelSession, method)
        }

        val authContextId = checkNotNull(channelSession.authContextId) { "AuthContext missing after processing Completed for $toolId" }
        authContextService.applyEvidence(authContextId, outcome.amr, outcome.factorTypes, effectiveAcr)
        sessionManagementService.recordEvent(
            channelSession.channelSessionId, processSession.processSessionId, "TOOL_COMPLETED:$toolId", "orchestrator"
        )

        return resolveNext(processSession, channelSession, outcome)
    }

    /**
     * Re-derives what the process should offer for [category] from CURRENT state, without a
     * fresh ToolOutcome - used when the user abandons an already-activated tool ("Back"/
     * "Switch", docs/05-api.md #2: DELETE on the tool namespace). Reuses the exact same
     * candidate resolution the ENROLL/AUTH branches of resolveNext already use, since nothing
     * new has actually been proven - only the decision of which tool to try is reopened.
     *
     * Returns null when there is nothing to reoffer - either because [category] has no
     * candidate concept at all (IDENT: identification isn't "one of several ways to close a
     * gap", docs/04-orchestrierung.md only defines candidates for ENROLL/AUTH), or because the
     * candidate list came back empty. Both cases mean the same thing to the caller: fall back
     * to cancelling the whole process. This is deliberately NOT a `category == IDENT` check in
     * the caller - a category alone says what KIND of proof a tool gives, not whether there is
     * a prior step in THIS process to return to; that distinction belongs here, next to the
     * one place that already owns the category -> candidate-function mapping.
     */
    fun reofferForCategory(category: ToolCategory, processSession: ProcessSession, channelSession: ChannelSession): Result? {
        val query = candidateQueryFor(category, processSession, channelSession) ?: return null
        if (query.candidates.isEmpty()) return null

        val result = offerCandidates(query.candidates, query.context)
        if (result.next.type == "tool") processSession.setNextTool(result.next.toolId!!, result.next.step)
        else processSession.setNextFlow(result.next.context!!, result.next.step)
        sessionManagementService.updateProcessSession(processSession)
        return result
    }

    /** Candidate toolIds plus the flow-context they belong to; always both from the same category. */
    private data class CandidateQuery(val candidates: List<String>, val context: String)

    /**
     * Null means [category] has no candidate concept at all - currently only true for IDENT.
     * Checked FIRST, before touching AuthContext/account: a freshly activated IDENT tool
     * (nothing proven yet, no account resolved yet) must still short-circuit cleanly instead of
     * failing on preconditions it structurally never needed in the first place.
     *
     * Candidates and their flow-context live in ONE `when` here on purpose: both are derived
     * from the same category and must never drift apart into two separately-maintained mappings.
     */
    private fun candidateQueryFor(category: ToolCategory, processSession: ProcessSession, channelSession: ChannelSession): CandidateQuery? {
        if (category == ToolCategory.IDENT) return null

        val authContextId = checkNotNull(channelSession.authContextId) { "No AuthContext bound to channel ${channelSession.channelSessionId}" }
        val authContext = checkNotNull(authContextService.getAuthContext(authContextId)) { "AuthContext not found: $authContextId" }
        val evidence = AuthEvidence(authContext.currentAmr, authContext.currentFactorTypes)
        val account = checkNotNull(processSession.accountId?.let { accountService.findAccount(it) }) { "No account resolved for process ${processSession.processSessionId}" }
        val requiredAcr = effectiveRequiredAcr(channelSession, processSession)

        return when (category) {
            ToolCategory.ENROLL -> CandidateQuery(authPolicy.enrollmentCandidates(account, requiredAcr), "enrollment")
            ToolCategory.AUTH -> CandidateQuery(authPolicy.candidateTools(evidence, requiredAcr, account), "auth")
            ToolCategory.IDENT -> error("unreachable: guarded above")
        }
    }

    // Category-specific side effects (docs/04-orchestrierung.md #1, step 1) -----------------

    private fun handleIdentified(
        outcome: ToolOutcome.Completed.Identified,
        processSession: ProcessSession,
        channelSession: ChannelSession,
        method: String
    ): String? = when (processSession) {
        is RegistrationProcessSession -> handleIdentifiedDuringRegistration(outcome, processSession, channelSession, method)
        is StepUpProcessSession -> handleIdentifiedDuringStepUp(outcome, processSession, channelSession, method)
        else -> error("Identified is only valid during REGISTRATION or STEP_UP")
    }

    private fun handleIdentifiedDuringRegistration(
        outcome: ToolOutcome.Completed.Identified,
        processSession: RegistrationProcessSession,
        channelSession: ChannelSession,
        method: String
    ): String? {
        processSession.personId = outcome.personId

        val account = accountService.findOrCreateAccount(outcome.personId)
        processSession.accountId = account.accountId
        channelSession.accountId = account.accountId

        if (channelSession.authContextId == null) {
            val authContext = authContextService.createForAccount(account.accountId)
            channelSession.authContextId = authContext.authContextId
        }
        sessionManagementService.updateChannelSession(channelSession)

        accountService.addIdentification(
            account.accountId,
            method,
            outcome.achievedAcr,
            outcome.auditDetails.orEmpty() + mapOf(
                "channel" to channelSession.channel?.name,
                "processSessionId" to processSession.processSessionId.toString()
            )
        )
        return outcome.achievedAcr
    }

    /**
     * Re-identification as a step-up path (docs/04-orchestrierung.md, MANAGE_METHODS): unlike
     * REGISTRATION, the account is already known here - this must only ever CONFIRM that known
     * account, never switch to a different one. Without the personId check, a session that
     * merely proves loa1 could smuggle in someone else's KVNR/FSC and hijack a different account
     * outright, which is worse than the self-lockout this whole feature exists to close.
     */
    private fun handleIdentifiedDuringStepUp(
        outcome: ToolOutcome.Completed.Identified,
        processSession: StepUpProcessSession,
        channelSession: ChannelSession,
        method: String
    ): String? {
        val accountId = checkNotNull(processSession.accountId ?: channelSession.accountId) {
            "Identified during STEP_UP without a known account"
        }
        val account = checkNotNull(accountService.findAccount(accountId)) { "Account not found: $accountId" }
        if (account.personId != outcome.personId) {
            throw OrchestratorException.invalidState("Identifizierte Person passt nicht zum angemeldeten Konto")
        }
        processSession.accountId = accountId

        accountService.addIdentification(
            accountId,
            method,
            outcome.achievedAcr,
            outcome.auditDetails.orEmpty() + mapOf(
                "channel" to channelSession.channel?.name,
                "processSessionId" to processSession.processSessionId.toString()
            )
        )
        return outcome.achievedAcr
    }

    private fun handleEnrolled(
        outcome: ToolOutcome.Completed.Enrolled,
        processSession: ProcessSession,
        channelSession: ChannelSession,
        method: String
    ): String? {
        val accountId = checkNotNull(processSession.accountId) { "Enrolled outcome without an account bound to the process" }
        val authContextId = checkNotNull(channelSession.authContextId) { "Enrolled outcome without an AuthContext bound to the channel" }
        val authContext = checkNotNull(authContextService.getAuthContext(authContextId)) { "AuthContext not found: $authContextId" }

        // email is a deliberate exception (docs/02-domaenenmodell.md #5): its confirmed value
        // lives directly on Account, not in a module-owned enrollment row, so it must not also
        // be duplicated into the generic authenticationMethods[].details JSON blob.
        val email = outcome.auditDetails?.get("email") as? String
        val detailsForAccount = outcome.auditDetails.orEmpty().minus("email") + mapOf(
            "enrolledUnderAmr" to authContext.currentAmr,
            "channel" to channelSession.channel?.name
        )

        // Conditions of the setup - known only to the orchestrator, not the module.
        accountService.addAuthenticationMethod(
            accountId,
            method,
            outcome.enrollmentRef,
            enrolledUnderAcr = authContext.currentAcr,
            details = detailsForAccount
        )
        if (method == "email" && email != null) {
            accountService.confirmEmail(accountId, email)
        }
        // A real, provable credential now exists - a future new channel on this device can be
        // recognized and offered LOGIN, which still requires proving it (TAN/password/...) via
        // the normal auth-* flow; this link grants no trust by itself. Deliberately NOT done at
        // mere Identified (docs discussion): identification alone leaves nothing to challenge
        // the device with, so that must still force full re-identification. Deliberately NOT
        // gated on canAccountReach/isSatisfied for the CURRENT channel's own requiredAcr either:
        // whether ONE method is enough for some elevated floor is an orthogonal question the
        // normal MFA/candidateTools loop already answers once LOGIN starts - it must not block
        // recognizing the device at all just because this one channel happens to want more.
        sessionManagementService.linkDeviceToAccount(channelSession.bindingKeyRef!!, accountId)
        return outcome.achievedAcr
    }

    private fun handleAuthenticated(
        outcome: ToolOutcome.Completed.Authenticated,
        processSession: ProcessSession,
        channelSession: ChannelSession,
        method: String
    ): String? {
        // outcome.accountId is set ONLY by lookup-based auth-*-lookup tools (docs/04-orchestrierung.md,
        // lookup-based login), which resolve the account themselves from a submitted email -
        // ordinary device-bound auth-* tools leave it null because the account is already known
        // from the channel/process by the time they can even activate.
        val accountId = outcome.accountId
            ?: checkNotNull(processSession.accountId ?: channelSession.accountId) { "Authenticated outcome without a known account" }
        processSession.accountId = accountId
        channelSession.accountId = accountId

        if (channelSession.authContextId == null) {
            // Fresh login: start a new evidence trail rather than reuse a stale one.
            val authContext = authContextService.createForAccount(accountId)
            channelSession.authContextId = authContext.authContextId
        }
        sessionManagementService.updateChannelSession(channelSession)

        // A real credential just got proven for this device - link it now, same hook as
        // handleEnrolled's DeviceAccountLink write, so a future channel on this device is
        // recognized straight into ordinary LOGIN. Unconditional (not just outcome.accountId !=
        // null / lookup-based logins): a device that re-identified via ident-fsc and then proved
        // an ALREADY-enrolled method never goes through handleEnrolled either, so without this it
        // would never get linked and would be forced back through ident-fsc on every future
        // "auto" connect - linkDeviceToAccount is idempotent, so re-linking an already-linked
        // device here is a harmless no-op.
        sessionManagementService.linkDeviceToAccount(channelSession.bindingKeyRef!!, accountId)

        // Capping: a method can never authenticate to more trust than it was enrolled under.
        val usedMethod = checkNotNull(accountService.findActiveMethod(accountId, method)) { "No active method '$method' found for account $accountId" }
        return AcrLevels.min(outcome.achievedAcr, usedMethod.enrolledUnderAcr)
    }

    // next resolution (docs/04-orchestrierung.md #1, table) ---------------------------------

    private fun resolveNext(processSession: ProcessSession, channelSession: ChannelSession, outcome: ToolOutcome.Completed): Result {
        val authContext = authContextService.getAuthContext(channelSession.authContextId!!)!!
        val evidence = AuthEvidence(authContext.currentAmr, authContext.currentFactorTypes)
        val account = checkNotNull(processSession.accountId?.let { accountService.findAccount(it) }) { "No account resolved for process ${processSession.processSessionId}" }
        val requiredAcr = effectiveRequiredAcr(channelSession, processSession)

        val result = when (outcome) {
            is ToolOutcome.Completed.Identified ->
                if (processSession is StepUpProcessSession && authPolicy.isSatisfied(evidence, requiredAcr, account)) {
                    // Re-identification during a step-up (MANAGE_METHODS) already prices in its
                    // own full trust level - once that alone satisfies the floor, there is
                    // nothing left to prove, unlike REGISTRATION where identification is never
                    // sufficient by itself.
                    finishAsAuthenticated(processSession, channelSession, authContext.currentAcr)
                } else if (authPolicy.canAccountReach(account, requiredAcr)) {
                    // findOrCreateAccount (docs/05-api.md #2) reuses an existing account for a KVNR
                    // that already went through registration before. If that account can already
                    // reach requiredAcr via a method it has active, there is nothing left to enroll -
                    // offer proving that existing method instead of enrollmentCandidates, which would
                    // come back empty and dead-end the process (docs/04-orchestrierung.md #1).
                    offerCandidates(authPolicy.candidateTools(evidence, requiredAcr, account), "auth")
                } else {
                    offerCandidates(authPolicy.enrollmentCandidates(account, requiredAcr), "enrollment")
                }

            is ToolOutcome.Completed.Enrolled ->
                if (processSession is ManageMethodsProcessSession) {
                    // Voluntary enrollment (docs/04-orchestrierung.md): finishing never depends
                    // on canAccountReach/isSatisfied - the channel was already AUTHENTICATED
                    // before this started. One Enrolled outcome ends it; add another by calling
                    // POST .../methods again.
                    finishAsAuthenticated(processSession, channelSession, authContext.currentAcr)
                } else if (authPolicy.canAccountReach(account, requiredAcr) && authPolicy.isSatisfied(evidence, requiredAcr, account)) {
                    finishAsAuthenticated(processSession, channelSession, authContext.currentAcr)
                } else {
                    offerCandidates(authPolicy.enrollmentCandidates(account, requiredAcr), "enrollment")
                }

            is ToolOutcome.Completed.Authenticated ->
                if (authPolicy.isSatisfied(evidence, requiredAcr, account)) {
                    finishAsAuthenticated(processSession, channelSession, authContext.currentAcr)
                } else {
                    offerCandidates(authPolicy.candidateTools(evidence, requiredAcr, account), "auth")
                }
        }

        // The response's `next` must match what's persisted, or the next request (which reads
        // it back from the ProcessSession) rejects a toolId the client was just told to use.
        if (result.next.type == "tool") processSession.setNextTool(result.next.toolId!!, result.next.step)
        else processSession.setNextFlow(result.next.context!!, result.next.step)
        sessionManagementService.updateProcessSession(processSession)
        return result
    }

    private fun offerCandidates(candidates: List<String>, context: String): Result {
        val offer = CandidateOffering.resolve(candidates, context)
        return Result(offer.next, offer.stepData)
    }

    private fun finishAsAuthenticated(processSession: ProcessSession, channelSession: ChannelSession, achievedAcr: String?): Result {
        if (processSession is StepUpProcessSession) {
            processSession.achievedAcr = achievedAcr
        }
        processSession.consume()
        channelSession.state = ChannelState.AUTHENTICATED
        sessionManagementService.updateChannelSession(channelSession)
        return Result(Next.flow("authentication", "authenticated"))
    }

    private fun effectiveRequiredAcr(channelSession: ChannelSession, processSession: ProcessSession): String {
        val channelFloor = channelSession.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
        return if (processSession is StepUpProcessSession) {
            AcrLevels.max(channelFloor, processSession.requiredAcr)
        } else {
            channelFloor
        }
    }

}

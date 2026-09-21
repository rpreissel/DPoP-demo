package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Everything the JourneyLog's `detail` column is filled with - pure shaping of what a
 * [JourneyEvent], [Transition] or [Action] already determined. Deliberately split off
 * [JourneyService] so the service itself stays the state machine and nothing else; this
 * class never decides anything.
 *
 * Where a detail needs live data (which methods an account currently holds, which
 * candidates a state offers) it reads through the same ports the service itself would
 * use - and the one piece of routing the log shows, the "next", is never re-derived here:
 * [JourneyService] passes the already-computed [Next] in as a lazy thunk, so the log can
 * never disagree with what routing actually returned (docs/04-orchestrierung.md #4:
 * one function, so the two can never disagree).
 */
@Component
class JourneyLogDetails(
    private val toolRegistry: ToolHandlerRegistry,
    private val accountService: AccountService
) {

    fun methodEvidenceDetail(methods: List<MethodEvidence>): List<Map<String, Any?>> =
        methods.map { mapOf("method" to it.method.value, "loa" to it.loa.value, "factorTypes" to it.factorTypes.map { t -> t.name }, "amrSourceId" to it.amrSourceId) }

    /** The extra, event-specific detail worth keeping in the JourneyLog - which tool was involved, and how the outcome/answer read. */
    fun eventDetail(event: JourneyEvent): Map<String, Any?> = when (event) {
        is JourneyEvent.Completed -> mapOf("toolId" to event.tool.toolId, "method" to event.tool.method) + outcomeDetail(event.outcome)
        is JourneyEvent.Abandoned -> mapOf("toolId" to event.tool.toolId)
        is JourneyEvent.Answered -> mapOf("answer" to event.answer)
        is JourneyEvent.SubJourneyFinished -> mapOf("subIntent" to event.intent.name, "achievedAcr" to event.achievedAcr)
        is JourneyEvent.SubJourneyCancelled -> mapOf("subIntent" to event.intent.name)
        JourneyEvent.Started -> emptyMap()
        JourneyEvent.EvidenceReported -> emptyMap()
        JourneyEvent.ActionCompleted -> emptyMap()
    }

    /** Everything a completed tool run determined - the variant-specific fields, not just the common amr/achievedAcr/factorTypes. */
    private fun outcomeDetail(outcome: ToolOutcome.Completed): Map<String, Any?> {
        val common = mapOf(
            "outcome" to outcome::class.simpleName,
            "amr" to outcome.amr,
            "achievedAcr" to outcome.achievedAcr,
            "factorTypes" to outcome.factorTypes.map { it.name }
        )
        val specific = when (outcome) {
            is ToolOutcome.Completed.Identified -> mapOf("personId" to outcome.personId)
            is ToolOutcome.Completed.Enrolled -> mapOf("enrollmentRef" to outcome.enrollmentRef.toString())
            is ToolOutcome.Completed.Authenticated -> mapOf("accountId" to outcome.accountId)
            is ToolOutcome.Completed.Approved -> emptyMap()
            // The attested attribute TYPES, never their values - the log is a debug trace, and a
            // confirmed address is exactly the kind of value that has no business in one.
            is ToolOutcome.Completed.Attested ->
                mapOf("attested" to outcome.claims.map { it.attributeType.wireName })
        }
        return common + specific
    }

    /**
     * Where the transition leads - the concrete follow-up (target state/sub-intent/action), not
     * just which [Transition] variant fired. [availableTools] and [next] come straight from
     * [JourneyService], the one routing authority, so the log shows what the client will see,
     * not just the internal state-class name - and never a next the routing itself wouldn't
     * have returned.
     */
    fun transitionDetail(
        transition: Transition,
        journey: AuthJourney,
        channel: ChannelSession,
        state: JourneyState,
        availableTools: Set<ToolId>,
        next: (JourneyState) -> Next
    ): Map<String, Any?> = when (transition) {
        is Transition.To -> {
            val candidates = transition.state.activatable(availableTools)
            mapOf(
                "decision" to "To",
                "toState" to transition.state::class.simpleName,
                "authCandidates" to candidates.map { toolId -> toolId to toolRegistry.descriptorOf(toolId).method }.toMap(),
                "next" to next(transition.state).let { n -> mapOf("type" to n.type, "toolId" to n.toolId, "context" to n.context, "step" to n.step) }
            )
        }
        is Transition.RequireSubJourney -> mapOf(
            "decision" to "RequireSubJourney", "subIntent" to transition.intent.name,
            // Demo/log-only: both concrete seed types happen to carry a targetAcr, but under two
            // unrelated sealed interfaces - a plain `when` here is fine, this is observability, not
            // the seeding contract itself (see Transition.RequireSubJourney's own doc).
            "targetAcr" to when (val seed = transition.seedWith) {
                is StepUpState -> seed.targetAcr
                is ReIdentifyState -> seed.targetAcr
                else -> null
            }
        )
        // [state] is whatever was active right BEFORE this transition - e.g. the RestoreData
        // Anfangs-Übergang can leave a fresh channel Authenticated on its very first Started, with
        // no intervening `To` entry to show what could still have been offered. Without
        // authCandidates here, that case's log would go from "here's the seeded evidence" straight
        // to "Authenticated" with no trace of what else was available - misleadingly emptier than
        // the App channel's own Authenticated log entries ever were.
        Transition.Authenticated -> mapOf(
            "decision" to "Authenticated",
            "authCandidates" to state.activatable(availableTools)
                .map { toolId -> toolId to toolRegistry.descriptorOf(toolId).method }.toMap()
        )
        is Transition.Perform -> mapOf("decision" to "Perform", "action" to transition.action::class.simpleName) +
            actionDetail(transition.action, journey, channel)
        Transition.Logout -> mapOf("decision" to "Logout")
        Transition.Cancel -> mapOf("decision" to "Cancel")
        is Transition.Abort -> mapOf("decision" to "Abort", "reason" to transition.reason)
    }

    /**
     * What an [Action] carries worth keeping in the log - mainly, for [Action.RevokeAuthMethod],
     * which method/account it actually names (the class name alone doesn't say which instance).
     *
     * The tool-outcome actions contribute nothing of their own: device binding and account choice
     * are derived by the executor from the journey intent and the live binding at the moment it
     * acts, so an action carries no such decision to log - and the journey's own account/device
     * state already records what actually happened. Shared between the "Entry" seedAction log line
     * and every [Transition.Perform] logged in [JourneyService.advance].
     */
    fun actionDetail(action: Action, journey: AuthJourney, channel: ChannelSession): Map<String, Any?> = when (action) {
        is Action.RecordIdentification, is Action.AdoptAttestation -> emptyMap()
        is Action.AdoptCredential -> emptyMap()
        is Action.AcceptProof -> emptyMap()
        is Action.RecordApproval -> emptyMap()
        is Action.ApplyRestoredEvidence -> mapOf("source" to action.source, "methods" to methodEvidenceDetail(action.methods))
        is Action.RevokeAuthMethod -> {
            val accountId = journey.accountId ?: channel.accountId
            val target = accountId?.let { accountService.findAccount(it) }
                ?.authenticationMethods?.firstOrNull { it.id == action.methodInstanceId }
            mapOf("methodInstanceId" to action.methodInstanceId, "method" to target?.method, "label" to target?.label)
        }
        // The account these two act on is the session's own (they carry none of their own any
        // more, see Action.LinkDevice) - logged from the same place the executor reads it, so the
        // log says what actually happened rather than what a strategy once intended.
        is Action.LinkDevice, is Action.DeleteAccount ->
            mapOf("accountId" to (journey.accountId ?: channel.accountId))
    }
}

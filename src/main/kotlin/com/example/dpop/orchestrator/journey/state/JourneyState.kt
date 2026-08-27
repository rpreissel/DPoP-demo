package com.example.dpop.orchestrator.journey.state

import java.util.UUID

/**
 * The position on the path, together with the attributes that hold at exactly this position
 * (docs/04-orchestrierung.md #1). A plain status word would say "user is choosing a method"; a
 * [JourneyState] also says WHICH ones were offered and which the user has already declined.
 *
 * Every intent owns its own sealed set - the states of MANAGE_AUTH_METHODS make no sense for
 * LOOKUP_LOGIN and are not expressible there. A forgotten position is therefore a compile error in
 * a `when`, not a plausible-looking runtime default. Each intent's set lives in its own file
 * (`FastAccessState.kt`, `LookupLoginState.kt`, `StepUpState.kt`, `ManageAuthMethodsState.kt`) next
 * to this shared base.
 *
 * This is also the single source for two questions that otherwise drift apart: "which tool may
 * the client activate now?" and "where do I send them next?". Both are answered by
 * [activatable] - see [JourneyService.nextOf].
 */
sealed interface JourneyState {
    /**
     * Empty for states that wait on something other than a tool (e.g. a sub-journey). [availableTools]
     * is applied here, and only here (docs/03-tool-architektur.md, availability) - so a tool that
     * became unavailable after this state was written (backend kill-switch flipped while the state
     * sat unread) disappears from every caller (next-resolution, stepData.options, activation
     * membership check) without the state itself ever being recomputed.
     */
    fun activatable(availableTools: Set<String>): Set<String>

    /** The tool that is actually running, once one has been activated. */
    val active: ToolRef?

    /** `next.context` of the orchestrator-owned page this state maps to. */
    val selectionContext: String

    /** `next.step` of that page. */
    val selectionStep: String
        get() = "selectMethod"

    /**
     * The same state with a different running tool. Declared per state rather than derived
     * reflectively: a state that structurally cannot host a tool (a confirmation, a parked wish)
     * says so by ignoring this, and the compiler forces every new state to make that choice.
     */
    fun withActive(active: ToolRef?): JourneyState
}

/**
 * Which ToolSession is authorized to act as [toolId] right now - not just any ToolSession row
 * with a matching toolId. Without the id, an orphaned/superseded ToolSession (e.g. from a
 * duplicate activation request) would keep passing a toolId-only check even though its own
 * tool-module data was never populated, surfacing as a confusing "Unknown tool session" error
 * deep inside the module instead of a clean 409 at the boundary.
 */
data class ToolRef(val toolId: String, val toolSessionId: UUID, val step: String)

/** Shared shape of every state that offers a set of tools and remembers what was declined. */
sealed interface OfferingState : JourneyState {
    val offered: List<String>
    val declined: Set<String>

    override fun activatable(availableTools: Set<String>): Set<String> = (offered.toSet() - declined) intersect availableTools

    /**
     * True once every offer here has been declined OR none of what's left is available. Only
     * fallback states ever reach this via decline (a mandatory state re-offers its full set instead
     * of narrowing it, so `declined` never grows there - see FastAccessStrategy.reoffer); the
     * availability half can also make a MANDATORY state's single remaining offer vanish, which is
     * exactly why every caller of [exhausted] falls back through the same chain as a decline would.
     */
    fun exhausted(availableTools: Set<String>): Boolean = activatable(availableTools).isEmpty()
}

/**
 * A state that pauses for an explicit accept/decline answer instead of a tool run (see
 * JourneyService.answer). Deliberately not sealed like [OfferingState]: this lets JourneyService
 * recognize "some state is waiting for a yes/no answer" without importing any intent's concrete
 * state, the same way [OfferingState] lets it recognize "some state offers tools" without knowing
 * which ones - a new yes/no action, whatever it decides to DO with the answer, never needs
 * JourneyService to change, only a new state implementing this and a new [Decision] case.
 */
interface AnswerableState : JourneyState

package com.example.dpop.orchestrator.journey.state

import com.example.dpop.tool_spi.ToolId
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
    fun activatable(availableTools: Set<ToolId>): Set<ToolId>

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

    /**
     * State-owned flags worth surfacing in the journey log (e.g. [com.example.dpop.orchestrator.
     * journey.state.KcSelectMethodState.accountAlreadyKnown]) - default empty so most states need
     * not override it. Lets `JourneyService` log them generically, without downcasting to any
     * concrete state to reach a field only that one carries.
     */
    val logDetail: Map<String, Any?> get() = emptyMap()
}

/**
 * Which ToolSession is authorized to act as [toolId] right now - not just any ToolSession row
 * with a matching toolId. Without the id, an orphaned/superseded ToolSession (e.g. from a
 * duplicate activation request) would keep passing a toolId-only check even though its own
 * tool-module data was never populated, surfacing as a confusing "Unknown tool session" error
 * deep inside the module instead of a clean 409 at the boundary.
 */
data class ToolRef(val toolId: ToolId, val toolSessionId: UUID, val step: String)

/** Shared shape of every state that offers a set of tools and remembers what was declined. */
sealed interface OfferingState : JourneyState {
    val offered: List<ToolId>
    val declined: Set<ToolId>

    /**
     * Backend-authored heading for the selection screen shown when more than one candidate is
     * offered - same reasoning as [AnswerableState.prompt]: the app channel is a mobile app with
     * week-long release cycles, so this text must be able to change without an app release.
     * `selectionContext` names only the ADDRESS of that screen (shared across every intent
     * offering the same KIND of candidate, e.g. "auth") - it says nothing about what the user is
     * actually being asked here (log in vs. confirm an account deletion), which is exactly why
     * this can't be a shared default the way [AnswerableState.prompt] fully is.
     */
    val selectionTitle: String
    val selectionDescription: String? get() = null

    override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = (offered.toSet() - declined) intersect availableTools

    /**
     * True once every offer here has been declined OR none of what's left is available. Only
     * fallback states ever reach this via decline (a mandatory state re-offers its full set instead
     * of narrowing it, so `declined` never grows there - see FastAccessStrategy.reoffer); the
     * availability half can also make a MANDATORY state's single remaining offer vanish, which is
     * exactly why every caller of [exhausted] falls back through the same chain as a decline would.
     */
    fun exhausted(availableTools: Set<ToolId>): Boolean = activatable(availableTools).isEmpty()

    /**
     * This state with [toolId] added to [declined] and no tool running - the one narrowing step a
     * decline performs. Declared per state for the same reason [withActive] is: a `data class`
     * cannot inherit `copy`, and making every state say it explicitly is what lets the decline
     * itself live in ONE place ([com.example.dpop.orchestrator.journey.declineTool]).
     *
     * That matters beyond tidiness: "is anything left after this decline?" used to be answered by
     * each strategy on its own, and had already drifted into two different answers - six call
     * sites asked `(offered - declined).isEmpty()`, which ignores availability, while three asked
     * [exhausted]. The first kind cannot see a last remaining offer that an admin switch has made
     * unavailable, so it narrows into a state that renders an empty selection and sends the user
     * straight back to it instead of down the fallback chain.
     */
    fun declining(toolId: ToolId): OfferingState
}

/**
 * A state that pauses for an explicit accept/decline answer instead of a tool run (see
 * JourneyService.answer). Deliberately not sealed like [OfferingState]: this lets JourneyService
 * recognize "some state is waiting for a yes/no answer" without importing any intent's concrete
 * state, the same way [OfferingState] lets it recognize "some state offers tools" without knowing
 * which ones - a new yes/no action, whatever it decides to DO with the answer, never needs
 * JourneyService to change, only a new state implementing this and a new [Decision] case.
 */
interface AnswerableState : JourneyState {
    /** What the client renders while waiting - see [Prompt] for why this carries real content. */
    val prompt: Prompt

    /**
     * Every [AnswerableState], of any intent, renders through the exact same generic screen
     * (`stepData.prompt` alone decides title/labels/buttons) and is answered through the exact
     * same generic endpoint - so, like [JourneyState.selectionStep]'s "selectMethod" default, ONE
     * shared address for all of them, never a new one per intent.
     */
    override val selectionContext: String get() = "prompt"
    override val selectionStep: String get() = "confirm"
}

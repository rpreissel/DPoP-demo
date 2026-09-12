package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Into a login on this device as fast as possible, and in a way that works again next time
 * (docs/04-orchestrierung.md #3): a bevorzugtes Gerät, sonst eine Auswahl unter den vorhandenen
 * Verfahren, sonst eine frische Identifizierung.
 *
 * States 1-2 ([PreferredAuth], [AuthChoice]) form a FALLBACK chain of this journey's own: declining
 * moves to the next, more laborious way in. Once nothing here is left, this journey hands off to
 * REGISTER's own journey as a [com.example.dpop.orchestrator.journey.Transition.RequireSubJourney]
 * precondition (same idiom as the RE_IDENTIFY sub-journey) - identification, and everything a fresh
 * identification can trigger (email confirmation, the Web-only password obligation), is REGISTER's
 * job alone, never reproduced here.
 *
 * [AuthChoice] and [Enrolling] are shared value types with [RegisterState], not owned exclusively
 * by either: both journeys reach the exact same two questions once an account is in hand ("does it
 * already have something that closes the gap now?", "does it need a new method enrolled?") - see
 * their own doc for why sharing the VALUE, not the STRATEGY, is what actually removes the
 * arbitrary-looking coupling a subclass relationship used to create here.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = FastAccessState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = FastAccessState.PreferredAuth::class, name = "PreferredAuth"),
    JsonSubTypes.Type(value = AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = Enrolling::class, name = "Enrolling")
)
sealed interface FastAccessState : JourneyState {

    data object Start : FastAccessState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    /** Linked device with a matching device method: exactly one default suggestion. */
    data class PreferredAuth(val toolId: String, override val active: ToolRef? = null) : FastAccessState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override fun activatable(availableTools: Set<String>): Set<String> = setOf(toolId) intersect availableTools
        override val selectionContext: String get() = "auth"
    }
}

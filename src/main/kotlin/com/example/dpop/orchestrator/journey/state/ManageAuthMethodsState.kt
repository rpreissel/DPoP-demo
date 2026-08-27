package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The only intent without a policy goal: ONE successful enrollment ends it, regardless of the
 * level reached - the channel was already AUTHENTICATED. Adding a second method means a new
 * journey.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = ManageAuthMethodsState.AddRequested::class, name = "AddRequested"),
    JsonSubTypes.Type(value = ManageAuthMethodsState.RemoveRequested::class, name = "RemoveRequested"),
    JsonSubTypes.Type(value = ManageAuthMethodsState.Enrolling::class, name = "Enrolling")
)
sealed interface ManageAuthMethodsState : JourneyState {

    /**
     * The user's wish, before the loa2 gate has been evaluated - and the state the journey is
     * parked in while a step-up sub-journey runs. That is exactly why an intention survives the
     * detour: whoever wanted to remove a method and had to prove loa2 first removes it afterwards
     * without acting again. No separate "awaiting" state is needed - JourneyLifecycle.SUSPENDED
     * plus the child's parentJourneyId already say that, and a second copy could only drift.
     */
    data object AddRequested : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class RemoveRequested(val methodInstanceId: String) : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class Enrolling(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : ManageAuthMethodsState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
    }
}

package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = StepUpState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = StepUpState.AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = StepUpState.ReIdentifying::class, name = "ReIdentifying")
)
sealed interface StepUpState : JourneyState {
    /** The goal of THIS run - distinct from the channel's durable `acrFloor`. */
    val targetAcr: String

    data class Start(override val targetAcr: String, val startingAcr: String) : StepUpState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class AuthChoice(
        override val targetAcr: String,
        val startingAcr: String,
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : StepUpState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Zusätzlichen Nachweis erbringen"
    }

    /**
     * The way out of the ONE-METHOD dead end: an account with exactly one active auth method has
     * nothing to combine with, so after a fresh device-bound login (loa1 only) it could never
     * reach loa2. `ident-fsc` reaches loa2 on its own. Deliberately its own state rather than part
     * of [AuthChoice] - re-identification must never look like a generic login shortcut, only like
     * the exit from this one dead end.
     */
    data class ReIdentifying(
        override val targetAcr: String,
        val startingAcr: String,
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : StepUpState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Identifizieren Sie sich erneut"
    }
}

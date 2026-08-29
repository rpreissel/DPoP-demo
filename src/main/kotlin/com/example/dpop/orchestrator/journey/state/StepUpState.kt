package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = StepUpState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = StepUpState.AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = StepUpState.OfferReIdent::class, name = "OfferReIdent"),
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
     * "No active method reaches the target - re-identify instead?" A plain yes/no, asked before
     * ever falling through to [ReIdentifying] - see that state's doc for why this can't be silent.
     */
    data class OfferReIdent(override val targetAcr: String, val startingAcr: String) : StepUpState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt
            get() = Prompt.Confirm(
                title = "Erneut identifizieren?",
                description = "Mit den vorhandenen Anmeldeverfahren ist das geforderte Sicherheitsniveau " +
                    "nicht erreichbar. Sie können sich stattdessen erneut identifizieren, um es direkt zu erreichen.",
                confirmLabel = "Erneut identifizieren",
                cancelLabel = "Abbrechen"
            )
    }

    /**
     * The way out once no active method can close the gap (e.g. an account with exactly one
     * active auth method has nothing to combine with, so after a fresh device-bound login (loa1
     * only) it could never reach loa2) - `ident-fsc` reaches loa2 on its own. Always reached via
     * [OfferReIdent]'s explicit confirmation first, never silently: re-identification is a
     * heavier action than picking another factor, so a user who only wanted a quick step-up isn't
     * redirected into it without being asked.
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

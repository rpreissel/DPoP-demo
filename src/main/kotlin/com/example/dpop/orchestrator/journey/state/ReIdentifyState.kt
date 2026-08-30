package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Shared by FAST_ACCESS/LOOKUP_LOGIN/STEP_UP: the one place a fresh identification (`ident-fsc`/
 * `ident-eid`, reaching `loa2`/`loa3` on its own) is offered as a last resort once no active
 * method can close a gap toward [targetAcr] on its own. Always reached via [OfferReIdent]'s
 * explicit confirmation first - re-identification is a heavier action than picking another
 * factor, so it's never a silent fallback (`ReIdentifyStrategy.interpret` always confirms the
 * account already known to the caller, never adopts a different one).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = ReIdentifyState.OfferReIdent::class, name = "OfferReIdent"),
    JsonSubTypes.Type(value = ReIdentifyState.Identifying::class, name = "Identifying")
)
sealed interface ReIdentifyState : JourneyState {
    /** The goal this sub-journey was started for - not the channel's durable floor. */
    val targetAcr: String

    /**
     * The channel's own acr right before this sub-journey started - `"none"` for a not-yet-
     * authenticated channel (FAST_ACCESS/LOOKUP_LOGIN), a real level for an already-AUTHENTICATED
     * one (STEP_UP). [ReIdentifyStrategy.onCancel] reads this to fall back correctly either way,
     * since RE_IDENTIFY itself has no fixed answer to "what state after giving up" the way an
     * always-post-auth intent like STEP_UP does.
     */
    val startingAcr: String

    data class OfferReIdent(override val targetAcr: String, override val startingAcr: String) : ReIdentifyState, AnswerableState {
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

    data class Identifying(
        override val targetAcr: String,
        override val startingAcr: String,
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : ReIdentifyState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Identifizieren Sie sich erneut"
    }
}

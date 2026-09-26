package com.example.dpop.orchestrator.domain.journey.state

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolId

/**
 * Shared by FAST_ACCESS/LOOKUP_LOGIN/STEP_UP: the one place a fresh identification (`ident-fsc`/
 * `ident-eid`, reaching `loa2`/`loa3` on its own) is offered as a last resort once no active
 * method can close a gap toward [targetAcr] on its own. Always reached via [OfferReIdent]'s
 * explicit confirmation first - re-identification is a heavier action than picking another
 * factor, so it's never a silent fallback (`ReIdentifyStrategy.interpret` always confirms the
 * account already known to the caller, never adopts a different one).
 */
sealed interface ReIdentifyState : JourneyState {
    /** The goal this sub-journey was started for - not the channel's durable floor. */
    val targetAcr: AcrLevel

    /** See [ReIdentifyState.forSubJourney]'s own doc - carried through from [OfferReIdent] into [Identifying] once offered. */
    val wording: Wording?

    /**
     * Caller-supplied wording override for a fresh RE_IDENTIFY run. The default text (kept when
     * this is `null`) frames it as a genuine "no active method reaches the target, re-identify as
     * a last resort" recovery - correct for the primary use case (FAST_ACCESS/LOOKUP_LOGIN/STEP_UP,
     * same reasoning as [StepUpState.forSubJourney]'s own `reason`), but factually wrong for
     * [com.example.dpop.orchestrator.journey.strategy.RegisterEnrollFirstStrategy]'s closing offer:
     * that account was never identified before, so "erneut" (again) is false, and nothing is
     * "nicht erreichbar" - every enrollment obligation is already discharged, identification there
     * is a plain optional extra, not a recovery path.
     */
    enum class Wording {
        /** A never-identified account offered identification as an optional extra - no "again", nothing unreachable. */
        OPTIONAL_IDENTIFICATION
    }

    companion object {
        /**
         * The seed a strategy hands [com.example.dpop.orchestrator.domain.journey.Transition.RequireSubJourney]
         * when no active method can close its own gap - the one place that knows how a fresh
         * RE_IDENTIFY run is represented, so callers never construct [OfferReIdent] themselves.
         * [wording] is `null` for every "no active method reaches the target" caller (keeps today's
         * default text) - see [Wording]'s own doc for the one caller that needs different framing.
         */
        fun forSubJourney(targetAcr: AcrLevel, startingAcr: AcrLevel, wording: Wording? = null): ReIdentifyState =
            OfferReIdent(targetAcr, startingAcr, wording)
    }

    /**
     * The channel's own acr right before this sub-journey started - `"none"` for a not-yet-
     * authenticated channel (FAST_ACCESS/LOOKUP_LOGIN), a real level for an already-AUTHENTICATED
     * one (STEP_UP). [ReIdentifyStrategy.onCancel] reads this to fall back correctly either way,
     * since RE_IDENTIFY itself has no fixed answer to "what state after giving up" the way an
     * always-post-auth intent like STEP_UP does.
     */
    val startingAcr: AcrLevel

    data class OfferReIdent(
        override val targetAcr: AcrLevel,
        override val startingAcr: AcrLevel,
        override val wording: Wording? = null
    ) : ReIdentifyState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val question: Question
            get() = when (wording) {
                Wording.OPTIONAL_IDENTIFICATION -> Question.Confirm(
                    title = Text("Identifizieren?"),
                    description = Text("Sie sind bereits angemeldet. Optional können Sie sich jetzt zusätzlich identifizieren."),
                    confirmLabel = Text("Identifizieren"),
                    cancelLabel = Text("Abbrechen")
                )
                null -> Question.Confirm(
                    title = Text("Erneut identifizieren?"),
                    description = Text(
                        "Mit den vorhandenen Anmeldeverfahren ist das geforderte Sicherheitsniveau " +
                            "nicht erreichbar. Sie können sich stattdessen erneut identifizieren, um es direkt zu erreichen."
                    ),
                    confirmLabel = Text("Erneut identifizieren"),
                    cancelLabel = Text("Abbrechen")
                )
            }
    }

    data class Identifying(
        override val targetAcr: AcrLevel,
        override val startingAcr: AcrLevel,
        override val offer: Offer,
        override val wording: Wording? = null
    ) : ReIdentifyState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = when (wording) {
            Wording.OPTIONAL_IDENTIFICATION -> Text("Identifikation (optional)")
            null -> Text("Erneute Identifikation erforderlich")
        }
        override val selectionDescription: Text get() = when (wording) {
            Wording.OPTIONAL_IDENTIFICATION -> Text("Wählen Sie ein Verfahren, um sich zu identifizieren.")
            null -> Text("Ihre bestehenden Anmeldeverfahren reichen für das geforderte Sicherheitsniveau nicht aus. Bitte identifizieren Sie sich erneut.")
        }
    }
}

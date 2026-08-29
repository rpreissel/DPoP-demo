package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The fallback chain from the most convenient to the most laborious way in, followed by the mandatory
 * states that make sure the next login works again (docs/04-orchestrierung.md #3).
 *
 * Two deliberately DIFFERENT transition rules live in one hierarchy, and that difference is named
 * here rather than left to a comment:
 * - [PreferredAuth], [AuthChoice], [Identifying] are FALLBACK states: declining moves on.
 * - [ConfirmingEmail], [Enrolling] are MANDATORY: only fulfilling moves on.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = FastAccessState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = FastAccessState.PreferredAuth::class, name = "PreferredAuth"),
    JsonSubTypes.Type(value = FastAccessState.AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = FastAccessState.Identifying::class, name = "Identifying"),
    JsonSubTypes.Type(value = FastAccessState.OfferReIdent::class, name = "OfferReIdent"),
    JsonSubTypes.Type(value = FastAccessState.ConfirmingEmail::class, name = "ConfirmingEmail"),
    JsonSubTypes.Type(value = FastAccessState.Enrolling::class, name = "Enrolling")
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

    /** Other authentication methods the account already has. */
    data class AuthChoice(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : FastAccessState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Wie möchten Sie sich anmelden?"
    }

    /**
     * Last fallback state: identification - here for login AND registration alike. Which one it
     * was is decided afterwards by `findOrCreateAccount`, which is exactly why a single state
     * covers both.
     */
    data class Identifying(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : FastAccessState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "registration"
        override val selectionStep: String get() = "selectIdentificationMethod"
        override val selectionTitle: String get() = "Wie möchten Sie sich identifizieren?"
    }

    /**
     * "No enrollment method closes the gap - re-identify instead?" (`ident-fsc` reaches loa2 on
     * its own). A plain yes/no, asked before ever falling through to a fresh [Identifying] round -
     * re-identification is a heavier action than picking a method to enroll, so it's never a
     * silent fallback.
     */
    data object OfferReIdent : FastAccessState, AnswerableState {
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

    data class ConfirmingEmail(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : FastAccessState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "E-Mail-Adresse bestätigen"
    }

    /**
     * [emailObligation] records that this run passed through [Identifying], i.e. it created or
     * adopted an account. Only then does the confirmed-email state apply afterwards: a chosen
     * password needs an identifier to hang off of, so such a run must not finish leaving
     * `enroll-password` permanently unreachable. A plain login is never blocked on it.
     *
     * It is an attribute of THIS state rather than a second one in front of it, because the
     * obligation is checked when enrolment is done - putting it first would take away
     * the choice of which method to set up.
     */
    data class Enrolling(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null,
        val emailObligation: Boolean = false
    ) : FastAccessState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "Wie möchten Sie sich zukünftig anmelden?"
    }
}

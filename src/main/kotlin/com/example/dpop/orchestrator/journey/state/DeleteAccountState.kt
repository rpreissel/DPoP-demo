package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * [ConfirmPending] always comes first, unconditionally - see `DeleteAccountStrategy`'s class doc
 * for why the loa2 GATE (same one as MANAGE_AUTH_METHODS) only applies AFTER accepting it, not
 * before. [ConfirmationRequired]'s re-proof is likewise unconditional in a way an ordinary
 * step-up is not: never skipped just because the channel happens to already carry loa2 - a
 * hijacked but already-authenticated session must not be able to delete the account on its own
 * say-so just because the level happens to already be high enough (unless that level was JUST
 * freshly proven via the gate's own step-up, in which case `DeleteAccountStrategy` deletes right
 * after that instead of demanding a second, redundant proof).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = DeleteAccountState.ConfirmPending::class, name = "ConfirmPending"),
    JsonSubTypes.Type(value = DeleteAccountState.ConfirmationRequired::class, name = "ConfirmationRequired")
)
sealed interface DeleteAccountState : JourneyState {

    /** "Do you really want to delete your account?" - a plain yes/no, before anything else is checked. */
    data object ConfirmPending : DeleteAccountState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt get() = Prompt.Confirm(
            title = "Konto wirklich löschen?",
            description = "Diese Aktion kann nicht rückgängig gemacht werden. Alle Ihre " +
                "Anmeldemethoden und Kontodaten werden endgültig gelöscht.",
            confirmLabel = "Konto löschen",
            cancelLabel = "Abbrechen",
            destructive = true
        )
    }

    /**
     * Re-prove any one active factor, fresh, right now - see [com.example.dpop.orchestrator.journey.CandidateTools.forReconfirmation]
     * for why this is not the ordinary step-up candidate set.
     */
    data class ConfirmationRequired(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : DeleteAccountState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        // "auth" like every other state offering DEVICE_AUTH candidates (FastAccessState.AuthChoice,
        // LookupLoginState.Credential, StepUpState.AuthChoice) - selectionContext names the KIND of
        // offer, not the intent, so the client's existing select-method routing needs no new entry.
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Bestätigen Sie Ihre Identität"
        override val selectionDescription: String? get() = "Wählen Sie ein Verfahren, um die Löschung zu bestätigen."
    }
}

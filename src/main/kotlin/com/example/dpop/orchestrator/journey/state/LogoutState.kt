package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The single state of a LOGOUT journey: a confirmation prompt. On accept the channel is logged
 * out; on decline the journey is cancelled and the channel stays AUTHENTICATED.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = LogoutState.ConfirmPending::class, name = "ConfirmPending")
)
sealed interface LogoutState : JourneyState {

    data object ConfirmPending : LogoutState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt get() = Prompt.Confirm(
            title = "Wirklich abmelden?",
            description = "Ihre aktuelle Sitzung wird beendet. Um erneut zuzugreifen, " +
                "müssen Sie sich wieder anmelden.",
            confirmLabel = "Abmelden",
            cancelLabel = "Abbrechen",
            destructive = false
        )
    }
}

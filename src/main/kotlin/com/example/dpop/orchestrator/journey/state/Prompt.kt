package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * What an [AnswerableState] shows while it waits - authored entirely by the backend, not the
 * client. The app channel is a mobile app with week-long release cycles, so any text a screen
 * needs must be able to change without an app release; [AnswerableState.prompt] is the one place
 * that content travels (as `stepData.prompt`), while `next.context`/`next.step` stay pure
 * addresses the client never has to interpret to render the screen.
 *
 * Sealed so a future second kind (e.g. a free-form choice among several answers) is a compile-time
 * decision at every call site, not a guessed string - [Confirm] is the only variant needed today.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = Prompt.Confirm::class, name = "Confirm")
)
sealed interface Prompt {
    val title: String
    val description: String?

    /** Answered via the existing generic `answer` endpoint with `"accept"` or `"decline"`. */
    data class Confirm(
        override val title: String,
        override val description: String?,
        val confirmLabel: String,
        val cancelLabel: String,
        /** Signals the client to render the confirming action as a destructive/dangerous one. */
        val destructive: Boolean = false
    ) : Prompt
}

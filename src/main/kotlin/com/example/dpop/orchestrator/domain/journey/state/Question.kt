package com.example.dpop.orchestrator.domain.journey.state

import com.example.dpop.texts.Text

/**
 * The question an [AnswerableState] asks while it waits - authored entirely by the backend, not the
 * client. The app channel is a mobile app with week-long release cycles, so any text a screen
 * needs must be able to change without an app release; [AnswerableState.question] is the one place
 * that content travels (as `stepData.prompt`), while `next.context`/`next.step` stay pure
 * addresses the client never has to interpret to render the screen.
 *
 * Sealed so a future second kind (e.g. a free-form choice among several answers) is a compile-time
 * decision at every call site, not a guessed string - [Confirm] is the only variant needed today.
 *
 * The domain form: no serialization here. On the wire it travels as `Prompt` (`ConfirmStep`,
 * discriminated by `kind`), mapped in `OrchestratorStepData.kt` (docs/ideen/fachkern-und-technik-trennen.md).
 */
sealed interface Question {
    val title: Text
    val description: Text?

    /** Answered via the existing generic `answer` endpoint with `"accept"` or `"decline"`. */
    data class Confirm(
        override val title: Text,
        override val description: Text?,
        val confirmLabel: Text,
        val cancelLabel: Text,
        /** Signals the client to render the confirming action as a destructive/dangerous one. */
        val destructive: Boolean = false
    ) : Question
}

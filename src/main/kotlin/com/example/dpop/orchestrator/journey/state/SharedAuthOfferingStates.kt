package com.example.dpop.orchestrator.journey.state

/**
 * Reached identically by [FastAccessState] and [RegisterState] - not because one borrows the
 * other's states, but because both journeys genuinely ask the same two questions at these points:
 * "does the account already have something that closes the gap right now?" ([AuthChoice]) and
 * "does it need a new method enrolled?" ([Enrolling]). A shared, intent-neutral value type for
 * exactly those two questions, alongside the intent-neutral [com.example.dpop.orchestrator.journey.
 * CandidateTools] that answers them - REGISTER and FAST_ACCESS each still own their own strategy
 * and transition function, they merely land on the same value here.
 */
data class AuthChoice(
    override val offered: List<String>,
    override val declined: Set<String> = emptySet(),
    override val active: ToolRef? = null
) : FastAccessState, RegisterState, OfferingState {
    override fun withActive(active: ToolRef?) = copy(active = active)
    override val selectionContext: String get() = "auth"
    override val selectionTitle: String get() = "Anmeldung – Verfahren wählen"
    override val selectionDescription: String get() = "Für Ihr Konto sind mehrere Anmeldeverfahren hinterlegt. Wählen Sie aus, wie Sie sich anmelden möchten."
}

/**
 * [emailObligation] records that this run passed through [RegisterState.Identifying], i.e. it
 * created or adopted an account. Only then does [RegisterState.ConfirmingEmail] apply afterwards: a
 * chosen password needs an identifier to hang off of, so such a run must not finish leaving
 * `enroll-password` permanently unreachable. A plain FAST_ACCESS login (which never sets this) is
 * never blocked on it - see [AuthChoice]'s own doc for why this state, too, is shared rather than
 * owned by one intent.
 *
 * It is an attribute of THIS state rather than a second one in front of it, because the
 * obligation is checked when enrolment is done - putting it first would take away the choice of
 * which method to set up.
 */
data class Enrolling(
    override val offered: List<String>,
    override val declined: Set<String> = emptySet(),
    override val active: ToolRef? = null,
    val emailObligation: Boolean = false
) : FastAccessState, RegisterState, OfferingState {
    override fun withActive(active: ToolRef?) = copy(active = active)
    override val selectionContext: String get() = "enrollment"
    override val selectionTitle: String get() = "Anmeldeverfahren einrichten"
    override val selectionDescription: String get() = "Damit Sie sich beim nächsten Mal schneller anmelden können, richten Sie jetzt ein Verfahren ein."
}

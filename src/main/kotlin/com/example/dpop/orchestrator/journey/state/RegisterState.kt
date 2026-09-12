package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * REGISTER's own journey: deliberately fresh identification, even on an already linked device
 * (docs/04-orchestrierung.md #2) - the device link lookup is suppressed and no existing account
 * binding is ever offered up front, unlike FAST_ACCESS's own [FastAccessState.PreferredAuth]/
 * [AuthChoice] shortcuts.
 *
 * It does NOT force a second account - the same KVNR still finds the same account again. "I want
 * to identify myself anew here" is a different goal from "get me in", which is why it is its own
 * intent with its own journey rather than a variant of FAST_ACCESS's.
 *
 * FAST_ACCESS runs this as a [com.example.dpop.orchestrator.journey.Transition.RequireSubJourney]
 * precondition whenever IT needs a fresh identification (no account at all, or an account whose
 * every method has just been declined) - same idiom as the RE_IDENTIFY sub-journey. [AuthChoice]
 * and [Enrolling] are shared value types with [FastAccessState] rather than owned here, because
 * both journeys genuinely reach the same two questions once an account is in hand (see their own
 * doc) - only [Identifying], [ConfirmingEmail] and [PasswordObligation] are REGISTER-exclusive.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = RegisterState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = RegisterState.Identifying::class, name = "Identifying"),
    JsonSubTypes.Type(value = RegisterState.ConfirmingEmail::class, name = "ConfirmingEmail"),
    JsonSubTypes.Type(value = RegisterState.PasswordObligation::class, name = "PasswordObligation"),
    JsonSubTypes.Type(value = AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = Enrolling::class, name = "Enrolling")
)
sealed interface RegisterState : JourneyState {

    data object Start : RegisterState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    companion object {
        /**
         * The seed a strategy hands [com.example.dpop.orchestrator.journey.Transition.
         * RequireSubJourney] when it needs a fresh identification - the one place that knows how a
         * fresh REGISTER run is represented, so callers never construct [Start] themselves (same
         * idiom as [ReIdentifyState.forSubJourney]).
         */
        fun forSubJourney(): RegisterState = Start
    }

    /**
     * Last-resort fallback state: identification - here for a login FAST_ACCESS couldn't shortcut
     * AND a genuine fresh registration alike. Which one it was is decided afterwards by
     * `findOrCreateAccount`, which is exactly why a single state covers both.
     */
    data class Identifying(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "registration"
        override val selectionStep: String get() = "selectIdentificationMethod"
        override val selectionTitle: String get() = "Identifikation erforderlich"
        override val selectionDescription: String get() = "Bitte identifizieren Sie sich, um Ihr Konto zu finden oder ein neues anzulegen."
    }

    data class ConfirmingEmail(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "E-Mail-Bestätigung ausstehend"
        override val selectionDescription: String get() = "Ihre E-Mail-Adresse muss noch bestätigt werden, damit sie als Anmeldeverfahren genutzt werden kann."
    }

    /**
     * Web-channel-only third obligation (docs/04-orchestrierung.md #8, [RegisterStrategy]): a
     * REGISTER run on the KEYCLOAK channel must always end up with a password credential, not just
     * any sufficient one - unlike [Enrolling.emailObligation], this does not cross channels either
     * (an APP REGISTER run never produces it) - it is a channel-scoped exception, applied by
     * [RegisterStrategy] itself right after the shared `afterEnrollment` re-check would otherwise
     * finish the run.
     *
     * Ordered AFTER [ConfirmingEmail], not before: `enroll-password` itself requires a confirmed
     * account email (`ToolDescriptor.requiresConfirmedEmail`, docs/03-tool-architektur.md #1) - it
     * cannot be a candidate at all before that obligation is discharged, so the only order that is
     * actually reachable is "sufficient method, then email, then password". Choosing
     * `enroll-password` directly in [Enrolling] is only possible once the email is already
     * confirmed for the same reason, and discharges this obligation before it is ever reached.
     */
    data class PasswordObligation(
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "Passwort einrichten"
        override val selectionDescription: String get() = "Für die Registrierung über das Web-Portal ist ein Passwort als Anmeldeverfahren erforderlich."
    }
}

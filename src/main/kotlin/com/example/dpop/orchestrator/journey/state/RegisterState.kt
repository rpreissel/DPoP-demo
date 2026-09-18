package com.example.dpop.orchestrator.journey.state

import com.example.dpop.tool_spi.ToolId
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
    JsonSubTypes.Type(value = RegisterState.ConfirmDeviceRebind::class, name = "ConfirmDeviceRebind"),
    JsonSubTypes.Type(value = RegisterState.ConfirmingEmail::class, name = "ConfirmingEmail"),
    JsonSubTypes.Type(value = RegisterState.PasswordObligation::class, name = "PasswordObligation"),
    JsonSubTypes.Type(value = AuthChoice::class, name = "AuthChoice"),
    JsonSubTypes.Type(value = Enrolling::class, name = "Enrolling")
)
sealed interface RegisterState : JourneyState {

    data object Start : RegisterState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
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
     * claim-based identity resolution, which is exactly why a single state covers both.
     */
    data class Identifying(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "registration"
        override val selectionStep: String get() = "selectIdentificationMethod"
        override val selectionTitle: String get() = "Identifikation erforderlich"
        override val selectionDescription: String get() = "Bitte identifizieren Sie sich, um Ihr Konto zu finden oder ein neues anzulegen."
    }

    /**
     * The just-(re)identified account is different from the one `DeviceAccountLink` currently
     * names for this device (docs/04-orchestrierung.md #2, "Zweitaccount"). Reached only once, as
     * early as possible - immediately after identification, before any method is offered - never
     * silently overwritten. [accountId] is the newly identified account, not the one about to be
     * displaced.
     */
    data class ConfirmDeviceRebind(val accountId: Long) : RegisterState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt get() = Prompt.Confirm(
            title = "Dieses Gerät ist bereits einem anderen Konto zugeordnet",
            description = "Wenn Sie fortfahren, wird dieses Gerät künftig nur noch diesem Konto " +
                "zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem " +
                "Gerät erneut identifizieren.",
            confirmLabel = "Gerät neu zuordnen",
            cancelLabel = "Abbrechen",
            destructive = true
        )
    }

    data class ConfirmingEmail(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "E-Mail-Bestätigung ausstehend"
        override val selectionDescription: String get() = "Ihre E-Mail-Adresse muss noch bestätigt werden - Ihr Konto wird darüber gefunden."
    }

    /**
     * Third obligation, on every channel (docs/04-orchestrierung.md #8, [RegisterStrategy]): a
     * REGISTER run must always end up with a password credential, not just any sufficient one.
     * Applies since confirming an email stopped being an enrollment - the address is account
     * infrastructure and leaves no KNOWLEDGE method behind, so the knowledge factor is named
     * outright instead of arriving as a side effect. Applied by [RegisterStrategy] right after the
     * shared `afterEnrollment` re-check would otherwise finish the run.
     *
     * Ordered AFTER [ConfirmingEmail], not before: `enroll-password` itself requires a confirmed
     * account email (`ToolDescriptor.requires`, a `ClaimRequirement(EMAIL, PROVEN)`,
     * docs/03-tool-architektur.md #1) - it
     * cannot be a candidate at all before that obligation is discharged, so the only order that is
     * actually reachable is "sufficient method, then email, then password". Choosing
     * `enroll-password` directly in [Enrolling] is only possible once the email is already
     * confirmed for the same reason, and discharges this obligation before it is ever reached.
     */
    data class PasswordObligation(
        override val offered: List<ToolId>,
        override val declined: Set<ToolId> = emptySet(),
        override val active: ToolRef? = null
    ) : RegisterState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: String get() = "Passwort einrichten"
        override val selectionDescription: String get() = "Für die Registrierung ist ein Passwort als Anmeldeverfahren erforderlich."
    }
}

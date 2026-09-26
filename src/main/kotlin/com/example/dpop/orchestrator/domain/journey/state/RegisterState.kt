package com.example.dpop.orchestrator.domain.journey.state

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.ToolId

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
 * FAST_ACCESS runs this as a [com.example.dpop.orchestrator.domain.journey.Transition.RequireSubJourney]
 * precondition whenever IT needs a fresh identification (no account at all, or an account whose
 * every method has just been declined) - same idiom as the RE_IDENTIFY sub-journey. [AuthChoice]
 * and [Enrolling] are shared value types with [FastAccessState] rather than owned here, because
 * both journeys genuinely reach the same two questions once an account is in hand (see their own
 * doc) - only [Identifying], [Assigning], [ConfirmingEmail] and [PasswordObligation] are
 * REGISTER-exclusive.
 */
sealed interface RegisterState : JourneyState {

    data object Start : RegisterState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    companion object {
        /**
         * The seed a strategy hands [com.example.dpop.orchestrator.domain.journey.Transition.
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
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "registration"
        override val selectionStep: String get() = "selectIdentificationMethod"
        override val selectionTitle: Text get() = Text("Identifikation erforderlich")
        override val selectionDescription: Text get() = Text("Bitte identifizieren Sie sich, um Ihr Konto zu finden oder ein neues anzulegen.")
    }

    /**
     * The just-(re)identified account is different from the one `DeviceAccountLink` currently
     * names for this device (docs/04-orchestrierung.md #2, "Zweitaccount"). Reached only once, as
     * early as possible - immediately after identification, before any method is offered - never
     * silently overwritten. [accountId] is the newly identified account, not the one about to be
     * displaced.
     *
     * Carries no accountId: `Action.LinkDevice` reads the account from the live session. A
     * persisted field nobody reads would still survive serialization looking authoritative, and
     * invite exactly that stale-snapshot use.
     */
    data object ConfirmDeviceRebind : RegisterState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val question: Question get() = Question.Confirm(
            title = Text("Dieses Gerät ist bereits einem anderen Konto zugeordnet"),
            description = Text("Wenn Sie fortfahren, wird dieses Gerät künftig nur noch diesem Konto zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem Gerät erneut identifizieren."),
            confirmLabel = Text("Gerät neu zuordnen"),
            cancelLabel = Text("Abbrechen"),
            destructive = true
        )
    }

    /**
     * The correlation step itself: an attestation established WHO the subject is, but no person
     * reference came with it (`ident-eid`: a card carries no KVNR, docs/12-entscheidungen.md
     * ADR-18), so the account is bound to nobody in the register yet. Offered right after the
     * attestation and before any enrollment, because the outcome decides what kind of account the
     * rest of the run is building.
     *
     * Deliberately NOT preceded by a Ja/Nein prompt: "darf ich die Nummer haben?" and the form
     * asking for it are the same question twice, and the form itself says what the number is for.
     * Abandoning the tool IS the "no" (`JourneyEvent.Abandoned` -> carry on), so this stays a
     * FALLBACK state, never an obligation - the run then finishes on an Interessent account
     * (ADR-10) with a fully attested identity, just without the register binding.
     */
    data class Assigning(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "registration"
        override val selectionTitle: Text get() = Text("Konto zuordnen")
        override val selectionDescription: Text get() =
            Text("Ihr Konto wird Ihrem Eintrag im Personenverzeichnis zugeordnet - per Versichertennummer oder, ohne sie, per Partnernummer.")
    }

    /**
     * FIRST mandatory step of a registration, before any enrollment is offered
     * (`AuthEnrollCore.confirmEmail`): a confirmed address is account infrastructure, not one of
     * the login methods competing for the user's choice, and `enroll-password` cannot even be a
     * candidate before it (`ClaimRequirement(EMAIL, PROVEN)`, docs/03-tool-architektur.md #1).
     * Skipped when no attesting tool is available right now; the obligation then survives into the
     * enrollment cascade (`AuthEnrollCore.afterEnrollment`) and is retried there.
     */
    data class ConfirmingEmail(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("E-Mail-Bestätigung ausstehend")
        override val selectionDescription: Text get() = Text("Ihre E-Mail-Adresse muss noch bestätigt werden - Ihr Konto wird darüber gefunden.")
    }

    /**
     * Third obligation, on every channel (docs/04-orchestrierung.md #8, [RegisterStrategy]): a
     * REGISTER run must not end below the level its own method management needs (loa2). Applies
     * since confirming an email stopped being an enrollment: the address is account infrastructure
     * and leaves no KNOWLEDGE method behind, so the knowledge factor is asked for outright instead
     * of arriving as a side effect. Only when nothing else covers it - a device credential carries
     * POSSESSION, KNOWLEDGE and INHERENCE by itself and makes this obligation moot. Applied by [RegisterStrategy] right after the
     * shared `afterEnrollment` re-check would otherwise finish the run.
     *
     * Ordered AFTER [ConfirmingEmail], not before: `enroll-password` itself requires a confirmed
     * account email (`ToolDescriptor.requires`, a `ClaimRequirement(EMAIL, PROVEN)`,
     * docs/03-tool-architektur.md #1) - it
     * cannot be a candidate at all before that obligation is discharged, so the only order that is
     * actually reachable is "email, then the login methods, then password". Choosing
     * `enroll-password` directly in [Enrolling] is only possible because the email is already
     * confirmed by then, and discharges this obligation before it is ever reached.
     */
    data class PasswordObligation(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("Passwort einrichten")
        override val selectionDescription: Text get() = Text("Für die Registrierung ist ein Passwort als Anmeldeverfahren erforderlich.")
    }
}

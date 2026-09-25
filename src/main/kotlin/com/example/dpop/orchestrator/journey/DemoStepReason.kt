package com.example.dpop.orchestrator.journey

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.KcSelectMethodState
import com.example.dpop.orchestrator.journey.state.LogoutState
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolId

/**
 * Demo-only reasoning for why the innermost journey's current step looks the way it does - either
 * why its tool became the automatic choice (only one candidate was offered, or a device was
 * recognized) or, once there's more than one, why a selection is being shown at all (e.g. the
 * account simply has several active methods) - deliberately kept OUT of the production
 * [JourneyState] hierarchy (docs/05-api.md #2: `demo` is the one sanctioned non-production
 * channel, everything else in that hierarchy is real contract). Never a substitute for the
 * screen's own backend-authored text (`OfferingState.selectionTitle`, `Prompt`) - that already
 * renders once on the actual screen, so repeating it here would only pad Struktur with the exact
 * same string the user is already looking at.
 *
 * The "several were offered" reasoning changes tense once a tool is [JourneyState.active]: the
 * choice already happened by then, and [OfferingState.activatable] would still report every
 * candidate as if the decision were still open (it does not depend on `active` at all - see that
 * state's own doc) - so the present-tense "a choice is being offered" wording would misleadingly
 * describe something already in the past.
 */
internal object DemoStepReason {
    fun explain(state: JourneyState, availableTools: Set<ToolId>): Text? {
        val activatable = state.activatable(availableTools)
        return when {
            activatable.isEmpty() -> null
            activatable.size == 1 -> when {
                state is FastAccessState.PreferredAuth -> Text("Gerät wiedererkannt - automatisch vorgeschlagen.")
                state is OfferingState && state.offered.size <= 1 -> Text("Nur dieses eine Verfahren ist verfügbar.")
                state is OfferingState -> Text("Die übrigen Verfahren sind aktuell nicht freigeschaltet.")
                else -> null
            }
            state is OfferingState && state.active == null -> Text("Mehrere aktive Verfahren vorhanden ({anzahl}) - zur Auswahl angeboten.", "anzahl" to activatable.size)
            state is OfferingState -> Text("Verfahren aus {anzahl} verfügbaren ausgewählt.", "anzahl" to activatable.size)
            else -> null
        }
    }

    /**
     * What this position of the journey is FOR - the orchestrator's reason for being here at all,
     * as opposed to [explain]'s "why this tool / why a choice". One case per concrete state and no
     * `else`: a new state without a purpose does not compile, so the demo column can never fall
     * silent on it.
     */
    fun purpose(state: JourneyState): Text = when (state) {
        is AuthChoice -> Text("Das Konto ist bekannt. Jetzt fehlt der Nachweis, dass Sie es sind - mit einem Verfahren, das schon eingerichtet ist.")
        is Enrolling -> Text("Das Konto hat noch kein Verfahren, das für künftige Anmeldungen reicht - deshalb wird jetzt eines eingerichtet.")

        FastAccessState.Start -> Text("Automatisch anmelden: die App nimmt den schnellsten Weg, den dieses Gerät erlaubt. Ohne verknüpftes Konto führt er über eine Registrierung.")
        is FastAccessState.PreferredAuth -> Text("Dieses Gerät ist mit einem Konto verbunden - der schnellste Weg ist das Verfahren dieses Geräts.")

        RegisterState.Start -> Text("Eine Registrierung beginnt immer mit der Frage, wer Sie sind.")
        is RegisterState.Identifying -> Text("Für ein Konto muss feststehen, wer Sie sind - deshalb zuerst eine Identifizierung.")
        RegisterState.ConfirmDeviceRebind -> Text("Dieses Gerät gehört schon zu einem anderen Konto - bevor es umgehängt wird, wird gefragt.")
        is RegisterState.Assigning -> Text("Die Identität steht fest. Mit der Versichertennummer lässt sie sich einer versicherten Person zuordnen - freiwillig.")
        is RegisterState.ConfirmingEmail -> Text("Ein neues Konto braucht eine bestätigte E-Mail-Adresse - sie ist Voraussetzung für ein Passwort.")
        is RegisterState.PasswordObligation -> Text("Für die Anmeldung im Browser braucht das Konto noch ein Passwort.")

        RegisterEnrollFirstState.EnrollFirstStart -> Text("Registrierung mit Einrichtung zuerst: Wer Sie sind, kann später noch geklärt werden.")
        RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind -> Text("Dieses Gerät gehört schon zu einem anderen Konto - bevor es umgehängt wird, wird gefragt.")
        is RegisterEnrollFirstState.EnrollFirstAttestingEmail -> Text("Registrierung mit Einrichtung zuerst: Pflichtschritt 1 ist eine bestätigte E-Mail-Adresse.")
        is RegisterEnrollFirstState.EnrollFirstEnrollingSms -> Text("Registrierung mit Einrichtung zuerst: Pflichtschritt 2 ist eine Handynummer für SMS.")
        is RegisterEnrollFirstState.EnrollFirstEnrolling -> Text("E-Mail und SMS reichen noch nicht für das verlangte Sicherheitsniveau - ein weiteres Verfahren fehlt.")
        is RegisterEnrollFirstState.EnrollFirstConfirmingEmail -> Text("Ein neues Konto braucht eine bestätigte E-Mail-Adresse - sie ist Voraussetzung für ein Passwort.")
        is RegisterEnrollFirstState.EnrollFirstPasswordObligation -> Text("Für die Anmeldung im Browser braucht das Konto noch ein Passwort.")

        LookupLoginState.Start -> Text("Anmeldung ohne Gerätebindung: Das Konto wird erst über Ihre Angaben gesucht.")
        is LookupLoginState.Credential -> Text("Anmeldung ohne Gerätebindung: E-Mail-Adresse oder Handynummer finden das Konto, das Verfahren beweist, dass Sie es sind.")
        is LookupLoginState.AdditionalFactor -> Text("Der erste Nachweis reicht nicht für das verlangte Sicherheitsniveau - ein zweites Verfahren fehlt.")
        LookupLoginState.OfferBinding -> Text("Anmeldung geschafft. Jetzt wird gefragt, ob dieses Gerät sich das Konto merken soll.")
        LookupLoginState.ConfirmDeviceRebind -> Text("Dieses Gerät gehört schon zu einem anderen Konto - bevor es umgehängt wird, wird gefragt.")

        is StepUpState.Start -> stepUpPurpose(state.targetAcr, state.startingAcr)
        is StepUpState.AuthChoice -> stepUpPurpose(state.targetAcr, state.startingAcr)

        is ReIdentifyState.OfferReIdent -> Text("Kein vorhandenes Verfahren erreicht Sicherheitsniveau {ziel} - bleibt nur, sich neu auszuweisen.", "ziel" to state.targetAcr.value)
        is ReIdentifyState.Identifying -> Text("Neu ausweisen für Sicherheitsniveau {ziel} - es muss dieselbe Person herauskommen wie im Konto.", "ziel" to state.targetAcr.value)

        ManageAuthMethodsState.AddRequested -> Text("Sie wollen ein Verfahren hinzufügen - das verlangt Sicherheitsniveau 2 in dieser Sitzung.")
        is ManageAuthMethodsState.RemoveRequested -> Text("Sie wollen ein Verfahren entfernen - das verlangt Sicherheitsniveau 2 in dieser Sitzung.")
        is ManageAuthMethodsState.RetractAttributeRequested -> Text("Sie wollen eine bestätigte Angabe zurücknehmen - das verlangt Sicherheitsniveau 2 in dieser Sitzung.")
        is ManageAuthMethodsState.Enrolling -> Text("Sicherheitsniveau 2 ist erreicht - jetzt wird das neue Verfahren eingerichtet.")

        DeleteAccountState.ConfirmPending -> Text("Löschen ist endgültig - deshalb zuerst eine Rückfrage.")
        is DeleteAccountState.ConfirmationRequired -> Text("Vor dem Löschen muss ein Verfahren frisch bestätigt werden - ein alter Nachweis reicht nicht.")

        LogoutState.ConfirmPending -> Text("Abmelden beendet die Sitzung - deshalb eine Rückfrage.")

        is ConfirmPeerLoginState.Requested -> Text("Ein Browser wartet auf Freigabe durch dieses Gerät. Zuerst wird geprüft, ob diese Sitzung dafür sicher genug ist.")
        is ConfirmPeerLoginState.ConfirmationRequired -> Text("Bevor dieses Gerät einen Browser freigibt, muss ein Verfahren frisch bestätigt werden.")
        is ConfirmPeerLoginState.Confirming -> Text("Diese Sitzung ist sicher genug - jetzt geben Sie die wartende Anmeldung im Browser frei.")
        ConfirmPeerLoginState.OfferLogout -> Text("Der Browser ist freigegeben. Diese Sitzung wurde nur dafür eröffnet - deshalb die Frage, ob sie offen bleiben soll.")

        is KcSelectMethodState.SelectMethod -> Text("Die Anmeldeseite verlangt einen Nachweis - angeboten wird jedes Verfahren, das im Browser geht.")

        // AnswerableState is deliberately not sealed (see its doc), so the compiler asks for it
        // although every concrete one already has its own case above - this only catches a yes/no
        // state added to some intent without a purpose of its own.
        is AnswerableState -> Text("Der Orchestrator wartet auf Ihre Antwort auf eine Rückfrage.")
    }

    private fun stepUpPurpose(target: AcrLevel, starting: AcrLevel) = Text(
        "Verlangt ist Sicherheitsniveau {ziel}, diese Sitzung hat {stand} - ein weiterer Nachweis fehlt.",
        "ziel" to target.value,
        "stand" to starting.value,
    )
}

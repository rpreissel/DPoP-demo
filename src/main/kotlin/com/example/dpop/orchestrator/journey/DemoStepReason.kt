package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState

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
    fun explain(state: JourneyState, availableTools: Set<String>): String? {
        val activatable = state.activatable(availableTools)
        return when {
            activatable.isEmpty() -> null
            activatable.size == 1 -> when {
                state is FastAccessState.PreferredAuth -> "Gerät wiedererkannt - automatisch vorgeschlagen."
                state is OfferingState && state.offered.size <= 1 -> "Nur dieses eine Verfahren ist verfügbar."
                state is OfferingState -> "Die übrigen Verfahren sind aktuell nicht freigeschaltet."
                else -> null
            }
            state is OfferingState && state.active == null -> "Mehrere aktive Verfahren vorhanden (${activatable.size}) - zur Auswahl angeboten."
            state is OfferingState -> "Verfahren aus ${activatable.size} verfügbaren ausgewählt."
            else -> null
        }
    }
}

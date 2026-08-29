package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState

/**
 * Demo-only reasoning for why a journey's current tool became the automatic choice (e.g. only one
 * candidate was ever offered, or a device was recognized) - deliberately kept OUT of the
 * production [JourneyState] hierarchy (docs/05-api.md #2: `demo` is the one sanctioned
 * non-production channel, everything else in that hierarchy is real contract). Callers only ask
 * this once [JourneyState.activatable] has already resolved to a single candidate; it never
 * decides that on its own.
 */
internal object DemoAutoPickNote {
    fun forSingleCandidate(state: JourneyState): String? = when {
        state is FastAccessState.PreferredAuth -> "Gerät wiedererkannt - automatisch vorgeschlagen."
        state is OfferingState && state.offered.size <= 1 -> "Nur dieses eine Verfahren ist verfügbar."
        state is OfferingState -> "Die übrigen Verfahren sind aktuell nicht freigeschaltet."
        else -> null
    }
}

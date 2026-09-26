package com.example.dpop.orchestrator.domain.journey

/**
 * Whether the journey is still running - orthogonal to where on the path it stands
 * ([JourneyState]).
 *
 * [SUSPENDED] exists for sub-journeys (docs/04-orchestrierung.md #6): while a precondition
 * journey runs, its parent waits. Keeping the parent out of [STARTED] is what preserves the
 * invariant "at most one running journey per channel" without a second lookup rule.
 */
enum class JourneyLifecycle {
    STARTED,
    SUSPENDED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    CONSUMED
}

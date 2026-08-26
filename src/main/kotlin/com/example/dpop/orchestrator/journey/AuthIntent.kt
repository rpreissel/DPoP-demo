package com.example.dpop.orchestrator.journey

/**
 * What the user wants to achieve, together with the strategy that leads them there
 * (docs/04-orchestrierung.md #1). The two are inseparable: "get me in" and "offer the device
 * first, then other methods, and identification only as a last resort" are one decision, not two.
 *
 * Deliberately NOT a description of what a run turned out to be. Whether a run was a registration
 * or a login is an observation about the path taken, never a goal chosen up front - which is why
 * there is no REGISTRATION/LOGIN pair here.
 */
enum class AuthIntent {
    /** Into a login on this device as fast as possible - and in a way that works again next time. */
    FAST,

    /** Deliberately fresh identification, even on an already linked device. */
    REGISTER,

    /** Log into an existing account without a paired device (classic web login). */
    LOGIN_LOOKUP,

    /** Raise the level. Only on an AUTHENTICATED channel. */
    STEP_UP,

    /** Add or remove authentication methods. Only on an AUTHENTICATED channel. */
    MANAGE;

    /** The three intents a client may name when entering a channel; STEP_UP/MANAGE are reached from an authenticated one. */
    val isEntryIntent: Boolean
        get() = this == FAST || this == REGISTER || this == LOGIN_LOOKUP

    companion object {
        /** `null` means the default. Unknown values are rejected by the caller, never silently mapped. */
        fun fromRequest(value: String?): AuthIntent? = when (value?.lowercase()) {
            null, "auto", "fast" -> FAST
            "register" -> REGISTER
            "login" -> LOGIN_LOOKUP
            else -> null
        }
    }
}

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

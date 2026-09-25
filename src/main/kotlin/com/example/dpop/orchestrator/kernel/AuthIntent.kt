package com.example.dpop.orchestrator.kernel

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
    FAST_ACCESS,

    /** Deliberately fresh identification, even on an already linked device. */
    REGISTER,

    /**
     * Log into an existing account without a paired device (classic web login) - the one intent
     * that does NOT link the device as a side effect of succeeding
     * ([bindsDeviceImplicitly]); it asks first (`LookupLoginState.OfferBinding`), because it is
     * chosen precisely by people who do not want to be recognized next time.
     */
    LOOKUP_LOGIN,

    /**
     * Entry intent for the kc-facade (docs/04-orchestrierung.md Abschnitt 3): always offers every
     * kc-usable tool as a single `selectMethod` step, no fallback chain, no enrollment - Keycloak
     * drives the rest of its own flow natively. Serves both Web-Kanal cases from the same
     * strategy: initial login (no account yet, resolves one like [LOOKUP_LOGIN]) and step-up
     * (account pre-set on the channel, like [FAST_ACCESS] with a recognized device).
     */
    KC_SELECT_METHOD,

    /** Raise the level. Only on an AUTHENTICATED channel. */
    STEP_UP,

    /** Add or remove authentication methods. Only on an AUTHENTICATED channel. */
    MANAGE_AUTH_METHODS,

    /**
     * Approve or decline a WEB-channel login that a `auth-qr`/`auth-qr-lookup` pairing is waiting
     * on (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN). Reachable BOTH ways at once, unlike every other
     * intent here: as an entry intent (a cold app scanning the QR, `POST /app/channels`) and, just
     * as directly, on an already-authenticated channel (an app already open when the QR is
     * scanned) - both converge on the exact same state/gate, so neither path needs its own
     * strategy. Never offers identification/registration even on the cold-entry path: the loa2 gate
     * is [STEP_UP], which only ever offers device-bound methods for an ALREADY known account
     * (`DeviceAccountLink`) - no account known at all means an immediate abort, never a fallback
     * into registering one. That is the one thing this intent decides for itself, before ever
     * reaching the shared gate.
     */
    CONFIRM_PEER_LOGIN,

    /** Delete the account itself, after a fresh re-confirmation. Only on an AUTHENTICATED channel. */
    DELETE_ACCOUNT,

    /** Log out with a confirmation prompt. Only on an AUTHENTICATED channel. */
    LOGOUT,

    /**
     * "No active method reaches the target - re-identify instead?" Never an entry intent, only
     * ever reached as another intent's [Transition.RequireSubJourney] once no active method can
     * close its own ACR gap (FAST_ACCESS/LOOKUP_LOGIN/STEP_UP alike) - one shared implementation
     * instead of three near-identical ones. What a fresh identification may mean here is not this
     * intent's decision at all: `Action.RecordIdentification`'s single handler always re-reads the account
     * in hand and gates any move to another one through `accountOf` (see [ReIdentifyStrategy]).
     */
    RE_IDENTIFY;

    /**
     * The intents a client may name when entering a channel; STEP_UP/MANAGE_AUTH_METHODS are
     * reached from an authenticated one only. [CONFIRM_PEER_LOGIN] is the one exception that is
     * BOTH - see its own doc.
     */
    val isEntryIntent: Boolean
        get() = this == FAST_ACCESS || this == REGISTER || this == LOOKUP_LOGIN || this == KC_SELECT_METHOD || this == CONFIRM_PEER_LOGIN

    /**
     * An APP channel entered with this intent starts from the account this device is linked to
     * (`DeviceAccountLink`) - when it is opened AND again after a cancel, one rule for both
     * (they used to disagree: a cancelled cold CONFIRM_PEER_LOGIN lost its account and aborted).
     * REGISTER and LOOKUP_LOGIN both mean "not the account this device already knows".
     */
    val startsFromDeviceLink: Boolean
        get() = this == FAST_ACCESS || this == CONFIRM_PEER_LOGIN

    /**
     * Whether succeeding on an APP channel links this device to the account as a side effect
     * (`DeviceAccountLink`, docs/09-dpop.md #3), or whether the intent asks the user first.
     * Only [LOOKUP_LOGIN] asks - see its own doc.
     *
     * An intent-level property rather than a flag on each Action: it never varies WITHIN an
     * intent, only between them, so a per-Action flag would be a value that can be set - and
     * therefore set wrongly - where nothing legitimately varies. Since device linking is one
     * shared implementation, a link that moves to another account also revokes that account's
     * device credentials for this key, so an accidental `true` is destructive, not just
     * convenient.
     *
     * Answers only "does this intent WANT to bind", never "is there a device to bind" - it could
     * not answer the second question even if it tried: the same intent runs on both channel types
     * ([REGISTER] is an entry intent on APP and on KEYCLOAK alike, docs/04-orchestrierung.md), so
     * "binds" would stop being a constant of the intent and become a function of the channel.
     *
     * The channel question is also the broader one: it applies to the EXPLICIT binding too (a
     * user who agrees on a KEYCLOAK channel still has no device), so it lives once in
     * `JourneyActionExecutor.linkDeviceTo`, which both paths go through. Two independent
     * questions, each answered where its information lives, combined in exactly one place.
     */
    val bindsDeviceImplicitly: Boolean
        get() = this != LOOKUP_LOGIN

    companion object {
        /**
         * `null` means the default (FAST_ACCESS). Otherwise matches an entry intent's own name,
         * case-insensitively (e.g. "register", "lookup_login") - no separate wire vocabulary to
         * keep in sync by hand; a new entry intent is automatically requestable under its own
         * name. STEP_UP/MANAGE_AUTH_METHODS are deliberately unreachable here (not entry intents,
         * only reached from an already-authenticated channel). Unknown values are rejected by the
         * caller, never silently mapped.
         */
        fun fromRequest(value: String?): AuthIntent? {
            if (value == null) return FAST_ACCESS
            return entries.firstOrNull { it.isEntryIntent && it.name.equals(value, ignoreCase = true) }
        }
    }
}

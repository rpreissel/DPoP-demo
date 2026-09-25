package com.example.dpop.orchestrator.session

enum class ChannelState {
    ANONYMOUS,
    REGISTERING,
    AUTHENTICATED,
    STEP_UP_REQUIRED,
    STEP_UP_IN_PROGRESS,
    LOGGED_OUT,
    EXPIRED;

    /**
     * This channelSessionId is dead for good (docs/02-domaenenmodell.md #3, docs/05-api.md #2):
     * `next` is unconditionally absent and a new channel needs a fresh `POST /channels` - never
     * resurrected by resume, cancel, or any strategy's own placeholder `next`.
     */
    val isTerminal: Boolean
        get() = this == LOGGED_OUT || this == EXPIRED

    /**
     * The channel has a login: it reached AUTHENTICATED, and a step-up only runs on top of one
     * (`JourneyService.start` sets STEP_UP_IN_PROGRESS on an authenticated channel only). This is
     * what a cancelled journey returns to - one rule for every intent, instead of each strategy
     * assuming where it runs (docs/04-orchestrierung.md, "Abbruch").
     */
    val isLoggedIn: Boolean
        get() = this == AUTHENTICATED || this == STEP_UP_IN_PROGRESS
}

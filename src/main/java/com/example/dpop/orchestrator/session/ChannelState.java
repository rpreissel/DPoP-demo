package com.example.dpop.orchestrator.session;

public enum ChannelState {
    ANONYMOUS,
    REGISTERING,
    AUTHENTICATED,
    STEP_UP_REQUIRED,
    STEP_UP_IN_PROGRESS,
    LOGGED_OUT,
    EXPIRED
}

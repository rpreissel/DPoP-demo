package com.example.dpop.orchestrator.session

enum class ProcessPurpose {
    REGISTRATION,
    LOGIN,
    STEP_UP,
    /** Voluntary account maintenance on an already-AUTHENTICATED channel: add or deactivate methods (docs/04-orchestrierung.md). */
    MANAGE_METHODS
}

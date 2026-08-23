package com.example.dpop.orchestrator.session

/** ProcessSession-level state; progress within one procedure lives in ToolState. */
enum class ProcessState {
    STARTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    CONSUMED
}

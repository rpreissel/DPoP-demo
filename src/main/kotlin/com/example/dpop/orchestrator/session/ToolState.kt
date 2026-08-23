package com.example.dpop.orchestrator.session

/** Derived per response from module data, never persisted directly (docs/03-tool-architektur.md). */
enum class ToolState {
    INPUT_REQUIRED,
    VERIFIED,
    FAILED,
    EXPIRED,
    CANCELLED
}

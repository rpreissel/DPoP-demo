package com.example.dpop.orchestrator.orchestration

/** Fixed activation step name per toolId (docs/06-ablaeufe.md); small and explicit on purpose (A11). */
object ToolSteps {
    private val startStepByToolId = mapOf(
        "ident-fsc" to "input",
        "enroll-sms" to "enroll",
        "auth-sms" to "auth"
    )

    fun startStepFor(toolId: String): String = startStepByToolId[toolId] ?: "start"
}

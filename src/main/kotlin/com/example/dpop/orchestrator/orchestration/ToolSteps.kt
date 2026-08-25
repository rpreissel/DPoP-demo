package com.example.dpop.orchestrator.orchestration

/** Fixed activation step name per toolId (docs/06-ablaeufe.md); small and explicit on purpose (A11). */
object ToolSteps {
    private val startStepByToolId = mapOf(
        "ident-fsc" to "input",
        "enroll-sms" to "enroll",
        "auth-sms" to "auth",
        "enroll-password" to "enroll",
        "auth-password" to "auth",
        "enroll-email" to "enroll",
        "auth-email" to "auth",
        "auth-sms-lookup" to "auth",
        "auth-password-lookup" to "auth",
        "enroll-device" to "enroll",
        "auth-device" to "auth"
    )

    fun startStepFor(toolId: String): String = startStepByToolId[toolId] ?: "start"
}

package com.example.dpop.orchestrator.api.v1

import org.springframework.http.HttpStatus

class OrchestratorException(
    val status: HttpStatus,
    val errorCode: String,
    message: String
) : RuntimeException(message) {

    companion object {
        fun notFound(message: String) =
            OrchestratorException(HttpStatus.GONE, "ATTEMPT_NOT_FOUND", message)

        fun conflict(message: String) =
            OrchestratorException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message)

        fun verificationFailed(message: String) =
            OrchestratorException(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_FAILED", message)

        fun forbidden(message: String) =
            OrchestratorException(HttpStatus.FORBIDDEN, "BINDING_MISMATCH", message)
    }
}

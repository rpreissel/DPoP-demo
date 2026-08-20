package com.example.dpop.orchestrator.api.v1

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class OrchestratorExceptionHandler {

    @ExceptionHandler(OrchestratorException::class)
    fun handleOrchestratorException(ex: OrchestratorException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(ex.status)
            .body(mapOf("error" to ex.errorCode, "message" to (ex.message ?: "")))
}

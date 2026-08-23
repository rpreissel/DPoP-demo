package com.example.dpop.orchestrator.api.v1

import com.example.dpop.tool_spi.UnresolvableReferenceException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Maps the error contract from docs/07-betrieb.md #1 onto exceptions raised anywhere in the call chain. */
@RestControllerAdvice
class OrchestratorExceptionHandler {

    @ExceptionHandler(OrchestratorException::class)
    fun handleOrchestratorException(ex: OrchestratorException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(ex.status).body(mapOf("error" to ex.errorCode, "message" to (ex.message ?: "")))

    /** Structurally invalid request (e.g. malformed phone number) - docs/07-betrieb.md #1: 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "BAD_REQUEST", "message" to (e.message ?: "")))

    /** Unknown/disallowed action on a resource in its current state - docs/07-betrieb.md #1: 409. */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "INVALID_STATE_TRANSITION", "message" to (e.message ?: "")))

    /** Fachlich unverarbeitbar, kein Nutzereingabefehler (unknown enrollmentRef) - docs/07-betrieb.md #1: 422. */
    @ExceptionHandler(UnresolvableReferenceException::class)
    fun handleUnresolvableReference(e: UnresolvableReferenceException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to "UNRESOLVABLE_REFERENCE", "message" to (e.message ?: "")))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "NOT_FOUND", "message" to (e.message ?: "")))

    /**
     * Two requests raced on the same ProcessSession/@Version row (e.g. a double tool-activation) -
     * docs/07-betrieb.md #1: 409, "concurrent process on same channel session". The loser should
     * retry against freshly-read state rather than see an unhandled 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleConcurrentModification(e: ObjectOptimisticLockingFailureException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "CONCURRENT_MODIFICATION", "message" to "Concurrent request on the same session - please retry.")
        )
}

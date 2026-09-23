package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.dpop.DpopValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.UnresolvableReferenceException
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Locale
import com.example.dpop.orchestrator.kernel.ErrorCode
import com.example.dpop.orchestrator.kernel.OrchestratorException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps the error contract from docs/07-betrieb.md #1 onto exceptions raised anywhere in the call chain. */
@RestControllerAdvice
class OrchestratorExceptionHandler {

    /**
     * Missing/invalid DPoP - docs/07-betrieb.md #1: 401. Thrown by [DpopBindingKeyResolver] during
     * argument resolution, before any controller method body runs; a `@RestControllerAdvice`
     * catches that same as an exception thrown from inside the method - one central place
     * instead of one `@ExceptionHandler` copy per controller.
     */
    @ExceptionHandler(DpopValidationException::class)
    fun handleDpopValidation(e: DpopValidationException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.UNAUTHORIZED, e.message ?: "")

    /** Missing/invalid Keycloak peer-auth assertion (docs/12-entscheidungen.md ADR-7) - same contract as DPoP: 401. */
    @ExceptionHandler(PeerAuthValidationException::class)
    fun handlePeerAuthValidation(e: PeerAuthValidationException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.UNAUTHORIZED, e.message ?: "")

    @ExceptionHandler(OrchestratorException::class)
    fun handleOrchestratorException(ex: OrchestratorException): ResponseEntity<ErrorResponse> =
        respond(ex.code, ex.message ?: "")

    /**
     * A value the CLIENT sent was rejected (a malformed phone number, an unknown acr level) -
     * docs/07-betrieb.md #1: 400. The message is meant for the caller and goes out as written.
     *
     * The rule this relies on: `require`/`IllegalArgumentException` only for rejected input,
     * `check`/`error()` for a broken internal assumption. Internal lookups ("Account not found")
     * used to throw this too and reached the client as a 400 with an internal id in the text.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, e.message ?: "")

    /** The body is not valid JSON or does not fit the request type. Spring's own shape otherwise. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, "Request body is not readable")

    /** A path or query value of the wrong type, e.g. a channelSessionId that is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, "Invalid value for '${e.name}'")

    /**
     * A broken internal assumption - `check`, `checkNotNull`, `error()`. That is a bug or a state
     * the code never expected, not something the caller did, so: 500, a neutral text, and the
     * details in the log only.
     *
     * This used to answer 409 INVALID_STATE_TRANSITION with the raw message. That dressed 217
     * internal checks up as a business conflict and sent texts like "auth-kobil enrollment 42 no
     * longer exists" to the client. Real conflicts are explicit:
     * `OrchestratorException.invalidState`. No test and no client relied on the old mapping -
     * checked by switching it to 500 against the full suite.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ErrorResponse> {
        log.error("Internal error", e)
        return respond(ErrorCode.INTERNAL_ERROR, "Internal error")
    }

    @ExceptionHandler(IdentityConflictException::class)
    fun handleIdentityConflict(e: IdentityConflictException): ResponseEntity<ErrorResponse> {
        log.warn("Identity claim conflict: {}", e.message)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, e.message ?: "Identity claim conflict")
    }

    // MVC also matches nested causes: this covers repository flushes AND transaction-commit errors.
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        // H2 reports schema-qualified names, optionally followed by " ON ..." (unique index) or
        // " INDEX <backing index> ON ..." (named unique constraint); never inspect values.
        val constraint = e.constraintName?.substringBefore(" ON ")?.substringBefore(" INDEX ")
            ?.substringAfterLast('.')?.trim('"')?.lowercase(Locale.ROOT)
        if (e.sqlState != "23505" || constraint !in ACCOUNT_BINDING_CONSTRAINTS) throw e
        log.warn("Concurrent account binding rejected by {}", constraint)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, "Identity claim conflicts with an existing account binding")
    }

    /** Fachlich unverarbeitbar, kein Nutzereingabefehler (unknown enrollmentRef) - docs/07-betrieb.md #1: 422. */
    @ExceptionHandler(UnresolvableReferenceException::class)
    fun handleUnresolvableReference(e: UnresolvableReferenceException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.UNRESOLVABLE_REFERENCE, e.message ?: "")

    /**
     * Two requests raced on the same AuthJourney/@Version row (e.g. a double tool-activation) -
     * docs/07-betrieb.md #1: 409, "concurrent process on same channel session". The loser should
     * retry against freshly-read state rather than see an unhandled 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleConcurrentModification(e: ObjectOptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        // The response is deliberately opaque, but a 409 must not be invisible in operations:
        // a genuine race and a self-inflicted one (an unexpected mid-request flush, say) look
        // identical from outside, and only the contended entity tells them apart.
        log.warn("Optimistic lock conflict on {} id={}", e.persistentClassName, e.identifier, e)
        return respond(ErrorCode.CONCURRENT_MODIFICATION, "Concurrent request on the same session - please retry.")
    }

    private fun respond(code: ErrorCode, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(code.httpStatus).body(ErrorResponse(code, message))

    private companion object {
        private val ACCOUNT_BINDING_CONSTRAINTS = setOf(
            "ux_anchor_value", "ux_anchor_account_type"
        )
        private val log = LoggerFactory.getLogger(OrchestratorExceptionHandler::class.java)
    }
}

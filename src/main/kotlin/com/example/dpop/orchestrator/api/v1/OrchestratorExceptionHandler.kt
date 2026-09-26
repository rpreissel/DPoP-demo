package com.example.dpop.orchestrator.api.v1

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.dpop.DpopValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.InvalidInputException
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
        respond(ErrorCode.UNAUTHORIZED, Text("Die Anfrage konnte nicht authentifiziert werden ({detail}).", "detail" to e.message))

    /** Missing/invalid Keycloak peer-auth assertion (docs/12-entscheidungen.md ADR-7) - same contract as DPoP: 401. */
    @ExceptionHandler(PeerAuthValidationException::class)
    fun handlePeerAuthValidation(e: PeerAuthValidationException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.UNAUTHORIZED, Text("Die Anfrage konnte nicht authentifiziert werden ({detail}).", "detail" to e.message))

    @ExceptionHandler(OrchestratorException::class)
    fun handleOrchestratorException(ex: OrchestratorException): ResponseEntity<ErrorResponse> {
        // The response carries words only; which session, account or tool it was is for the log.
        log.info("{}: {}", ex.code, ex.message)
        return respond(ex.code, ex.text)
    }

    /**
     * A value the CLIENT sent was rejected (a malformed phone number, an unknown acr level) -
     * docs/07-betrieb.md #1: 400. An [InvalidInputException] carries the words for the user; any
     * other rejection gets a neutral text, and its message goes to the log only - it may come from a
     * library and name classes or internals (review 2026-09, Phase F).
     *
     * The rule this relies on: `require`/`IllegalArgumentException` only for rejected input,
     * `check`/`error()` for a broken internal assumption. An internal lookup ("Account not found")
     * throwing this would reach the client as a 400 with an internal id in the text.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        if (e is InvalidInputException) return respond(ErrorCode.BAD_REQUEST, e.text)
        log.info("Rejected input: {}", e.message)
        return respond(ErrorCode.BAD_REQUEST, Text("Die Eingabe ist ungültig."))
    }

    /** The body is not valid JSON or does not fit the request type. Spring's own shape otherwise. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, Text("Die Anfrage ist nicht lesbar."))

    /** A path or query value of the wrong type, e.g. a channelSessionId that is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, Text("Ungültiger Wert für '{name}'.", "name" to e.name))

    /**
     * A broken internal assumption - `check`, `checkNotNull`, `error()`. That is a bug or a state
     * the code never expected, not something the caller did, so: 500, a neutral text, and the
     * details in the log only.
     *
     * Not 409 INVALID_STATE_TRANSITION: that would dress internal checks up as a business conflict
     * and send texts like "auth-kobil enrollment 42 no longer exists" to the client
     * (docs/07-betrieb.md #1). Real conflicts are explicit: `OrchestratorException.invalidState`.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ErrorResponse> {
        log.error("Internal error", e)
        return respond(ErrorCode.INTERNAL_ERROR, Text("Ein interner Fehler ist aufgetreten."))
    }

    @ExceptionHandler(IdentityConflictException::class)
    fun handleIdentityConflict(e: IdentityConflictException): ResponseEntity<ErrorResponse> {
        log.warn("Identity claim conflict: {}", e.message)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, e.text)
    }

    // Nested in a flush or commit exception it is found by handleUnexpected.
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        // H2 reports schema-qualified names, optionally followed by " ON ..." (unique index) or
        // " INDEX <backing index> ON ..." (named unique constraint); never inspect values.
        val constraint = e.constraintName?.substringBefore(" ON ")?.substringBefore(" INDEX ")
            ?.substringAfterLast('.')?.trim('"')?.lowercase(Locale.ROOT)
        if (e.sqlState != "23505" || constraint !in ACCOUNT_BINDING_CONSTRAINTS) return internalError(e)
        log.warn("Concurrent account binding rejected by {}", constraint)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, Text("Diese Identität gehört bereits zu einem anderen Konto."))
    }

    /** Fachlich unverarbeitbar, kein Nutzereingabefehler (unknown enrollmentRef) - docs/07-betrieb.md #1: 422. */
    @ExceptionHandler(UnresolvableReferenceException::class)
    fun handleUnresolvableReference(e: UnresolvableReferenceException): ResponseEntity<ErrorResponse> {
        log.info("{}: {}", ErrorCode.UNRESOLVABLE_REFERENCE, e.message)
        return respond(ErrorCode.UNRESOLVABLE_REFERENCE, e.text)
    }

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
        return respond(ErrorCode.CONCURRENT_MODIFICATION, Text("Gleichzeitige Anfrage in derselben Sitzung - bitte erneut versuchen."))
    }

    /**
     * Everything no handler above names - a database that is down, a failed transaction, a bug -
     * gets the same contract as a broken internal assumption: 500 INTERNAL_ERROR, a fixed text,
     * details in the log only (review 2026-09-26, B-4). Without it such a failure left with Spring
     * Boot's default body, not the `ErrorResponse` docs/07-betrieb.md #1 promises for every answer.
     *
     * Spring's own web exceptions (404 no such path, 405, 415, a missing parameter) carry their
     * status themselves ([org.springframework.web.ErrorResponse]); they are the framework's to
     * answer and are passed on unchanged.
     *
     * Spring matches the outermost exception first and only looks at causes when nothing matches;
     * with this handler something always does. A wrapped exception that has a rule of its own - a
     * binding conflict inside the flush's or commit's `DataIntegrityViolationException` - is
     * therefore looked for here.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is org.springframework.web.ErrorResponse) throw e
        return when (val known = generateSequence(e.cause) { it.cause }.firstOrNull { it is ConstraintViolationException || it is ObjectOptimisticLockingFailureException }) {
            is ConstraintViolationException -> handleConstraintViolation(known)
            is ObjectOptimisticLockingFailureException -> handleConcurrentModification(known)
            else -> internalError(e)
        }
    }

    private fun internalError(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error", e)
        return respond(ErrorCode.INTERNAL_ERROR, Text("Ein interner Fehler ist aufgetreten."))
    }

    private fun respond(code: ErrorCode, text: Text): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(code.httpStatus).body(ErrorResponse(code, text))

    private companion object {
        private val ACCOUNT_BINDING_CONSTRAINTS = setOf(
            "ux_anchor_value", "ux_anchor_account_type"
        )
        private val log = LoggerFactory.getLogger(OrchestratorExceptionHandler::class.java)
    }
}

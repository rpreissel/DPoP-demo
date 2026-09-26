package com.example.dpop.orchestrator.api.v1

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.InvalidInputException
import com.example.dpop.orchestrator.domain.ErrorCode
import com.example.dpop.tool_api.IdentityConflictException
import io.kotest.matchers.string.shouldNotContain
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.context.annotation.Profile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException

/**
 * Two requests racing on the same AuthJourney (e.g. a double tool-activation from React
 * StrictMode's double effect-invocation in dev) surface as ObjectOptimisticLockingFailureException
 * at commit time. Without this mapping that fell through as an unhandled 500; docs/07-betrieb.md
 * #1 already documents 409 for "concurrent process on same channel session".
 */
class OrchestratorExceptionHandlerTest : BehaviorSpec({

    given("an OrchestratorExceptionHandler") {
        val handler = OrchestratorExceptionHandler()

        then("domain binding conflicts use the existing 409 contract") {
            val response = handler.handleIdentityConflict(IdentityConflictException(Text("Person binding cannot change")))
            response.statusCode shouldBe HttpStatus.CONFLICT
            response.body?.error shouldBe ErrorCode.INVALID_STATE_TRANSITION
        }

        for (constraint in listOf("ux_anchor_value", "ux_anchor_account_type")) {
            then("known unique constraint $constraint maps to 409 without exposing SQL or values") {
                val response = handler.handleConstraintViolation(
                    ConstraintViolationException("private SQL data", SQLException("private value", "23505"), constraint)
                )
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.error shouldBe ErrorCode.INVALID_STATE_TRANSITION
                response.body?.text shouldBe Text("Diese Identität gehört bereits zu einem anderen Konto.")
            }
        }

        for ((constraint, state) in listOf(
            "ux_unrelated" to "23505",
            "ux_anchor_extra" to "23505",
            "ux_anchor" to "23502",
            null to "23505"
        )) {
            then("unrelated or unnamed integrity failures are internal errors that reveal nothing: $constraint / $state") {
                val failure = ConstraintViolationException("unrelated failure", SQLException("SQL error", state), constraint)
                val response = handler.handleConstraintViolation(failure)
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.error shouldBe ErrorCode.INTERNAL_ERROR
                response.body?.text.toString() shouldNotContain "SQL"
            }
        }

        for (atCommit in listOf(false, true)) {
            then("MVC finds nested binding violations at ${if (atCommit) "commit" else "flush"}") {
                val violation = ConstraintViolationException(
                    "duplicate", SQLException("duplicate", "23505"), "ACCOUNT.UX_ANCHOR_VALUE INDEX ACCOUNT.UX_ANCHOR_VALUE_INDEX_2 ON ACCOUNT.ANCHOR(...)"
                )
                val failure = if (atCommit) TransactionSystemException("commit failed", violation)
                    else DataIntegrityViolationException("flush failed", violation)
                MockMvcBuilders.standaloneSetup(FailingController(failure))
                    .setControllerAdvice(handler).build()
                    .perform(get("/failure"))
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"))
            }
        }

        then("a failure no handler names - the database is down - still answers in the error contract") {
            MockMvcBuilders.standaloneSetup(FailingController(DataAccessResourceFailureException("jdbc:h2:file:/secret/path unreachable")))
                .setControllerAdvice(handler).build()
                .perform(get("/failure"))
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect { it.response.contentAsString shouldNotContain "secret" }
        }

        then("Spring's own web errors keep their status - an unknown path is a 404, not a 500") {
            val notFound = NoResourceFoundException(HttpMethod.GET, "/nothing", "nothing")
            shouldThrow<NoResourceFoundException> { handler.handleUnexpected(notFound) } shouldBe notFound
        }

        then("a broken internal assumption is a 500 whose text reveals nothing") {
            val response = handler.handleIllegalState(IllegalStateException("auth-kobil enrollment 42 no longer exists"))
            response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body?.error shouldBe ErrorCode.INTERNAL_ERROR
            response.body?.text.toString() shouldNotContain "42"
        }

        then("rejected input without words of its own is a 400 with a neutral text - the message stays in the log") {
            val response = handler.handleIllegalArgument(IllegalArgumentException("Invalid UUID string: com.example.Internal"))
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            response.body?.error shouldBe ErrorCode.BAD_REQUEST
            response.body?.text?.args shouldBe emptyMap()
        }

        then("input rejected in the user's words keeps those words") {
            val words = Text("Ungueltige Telefonnummer")
            handler.handleIllegalArgument(InvalidInputException(words)).body?.text shouldBe words
        }

        `when`("two requests race on the same AuthJourney and Hibernate throws ObjectOptimisticLockingFailureException") {
            val response = handler.handleConcurrentModification(
                ObjectOptimisticLockingFailureException("process_session", "some-id")
            )

            then("it maps to 409 Conflict with error CONCURRENT_MODIFICATION") {
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.error shouldBe ErrorCode.CONCURRENT_MODIFICATION
            }
        }
    }
}) {
    @RestController
    @Profile("exception-handler-test")
    class FailingController(private val failure: RuntimeException) {
        @GetMapping("/failure")
        fun fail(): String = throw failure
    }
}

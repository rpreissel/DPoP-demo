package com.example.dpop.orchestrator.api.v1

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException

/**
 * Two requests racing on the same AuthJourney (e.g. a double tool-activation from React
 * StrictMode's double effect-invocation in dev) surface as ObjectOptimisticLockingFailureException
 * at commit time. Without this mapping that fell through as an unhandled 500; docs/07-betrieb.md
 * #1 already documents 409 for "concurrent process on same channel session".
 */
class OrchestratorExceptionHandlerTest : BehaviorSpec({

    given("an OrchestratorExceptionHandler") {
        val handler = OrchestratorExceptionHandler()

        `when`("two requests race on the same AuthJourney and Hibernate throws ObjectOptimisticLockingFailureException") {
            val response = handler.handleConcurrentModification(
                ObjectOptimisticLockingFailureException("process_session", "some-id")
            )

            then("it maps to 409 Conflict with error CONCURRENT_MODIFICATION") {
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.get("error") shouldBe "CONCURRENT_MODIFICATION"
            }
        }
    }
})

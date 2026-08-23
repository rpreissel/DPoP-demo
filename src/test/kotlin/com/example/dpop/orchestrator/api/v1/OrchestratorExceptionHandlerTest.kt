package com.example.dpop.orchestrator.api.v1

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException

/**
 * Two requests racing on the same ProcessSession (e.g. a double tool-activation from React
 * StrictMode's double effect-invocation in dev) surface as ObjectOptimisticLockingFailureException
 * at commit time. Without this mapping that fell through as an unhandled 500; docs/07-betrieb.md
 * #1 already documents 409 for "concurrent process on same channel session".
 */
class OrchestratorExceptionHandlerTest {

    private val handler = OrchestratorExceptionHandler()

    @Test
    fun concurrentModification_mapsToConflict() {
        val response = handler.handleConcurrentModification(
            ObjectOptimisticLockingFailureException("process_session", "some-id")
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body?.get("error")).isEqualTo("CONCURRENT_MODIFICATION")
    }
}

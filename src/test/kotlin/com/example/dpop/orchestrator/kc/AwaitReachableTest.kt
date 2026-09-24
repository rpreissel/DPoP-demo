package com.example.dpop.orchestrator.kc

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Mit simulierter Uhr: kein echtes Warten im Test. */
class AwaitReachableTest {

    private var clock = Instant.parse("2026-01-01T00:00:00Z")
    private val await = AwaitReachable(
        timeout = Duration.ofSeconds(10),
        interval = Duration.ofSeconds(2),
        now = { clock },
        sleep = { clock = clock.plus(it) },
    )

    @Test
    fun `wartet, bis die Abhaengigkeit antwortet`() {
        var calls = 0
        await.await("Keycloak") { if (++calls < 3) error("noch nicht da") }
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `gibt nach dem Timeout mit der letzten Ursache auf`() {
        assertThatThrownBy { await.await("Keycloak") { error("Verbindung abgelehnt") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("nicht erreichbar")
            .hasRootCauseMessage("Verbindung abgelehnt")
    }
}

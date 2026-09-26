package com.example.dpop.orchestrator

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * `dpop.events.incomplete` (docs/07-betrieb.md Abschnitt 7): how many event publications a listener
 * has not completed yet (`orchestrator.event_publication`, Spring Modulith's outbox). A handful is
 * normal; a number that only grows means a listener fails for every event - the Keycloak cleanup
 * after an account deletion, say - and the registry keeps them for a retry that never succeeds.
 *
 * Counted when scraped, over the completion-date index; not per request.
 */
@Component
class EventPublicationBacklog(jdbcTemplate: JdbcTemplate, meterRegistry: MeterRegistry) {
    init {
        Gauge.builder("dpop.events.incomplete") {
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.event_publication WHERE completion_date IS NULL",
                Long::class.java,
            )?.toDouble() ?: 0.0
        }
            .description("Event publications not yet completed by their listener")
            .register(meterRegistry)
    }
}

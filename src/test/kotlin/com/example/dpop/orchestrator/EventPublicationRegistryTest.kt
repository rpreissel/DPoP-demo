package com.example.dpop.orchestrator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Proves the Event Publication Registry is actually working in this application, not merely on the
 * classpath.
 *
 * The registry is what makes the Keycloak mirror recoverable (docs/07-betrieb.md Abschnitt 3a):
 * before an account's transaction commits, a row is written recording that a listener still owes
 * work, and it is only closed once that listener returns normally. Three things have to line up for
 * that - the `orchestrator.event_publication` table from the Flyway migration, the event
 * serializer, and the listener being an `@ApplicationModuleListener`. If any of them is
 * misconfigured, nothing fails loudly; the sync simply goes back to being silently lossy.
 *
 * So the test drives the failure case: a listener that throws must leave an open row behind.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EventPublicationRegistryTest.FailingListenerConfig::class)
class EventPublicationRegistryTest : BehaviorSpec() {

    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    init {
        given("a module listener that fails") {
            then("its publication stays open in orchestrator.event_publication") {
                val marker = UUID.randomUUID().toString()

                // Published inside a transaction: the registry records the publication as part of
                // the committing transaction, which is exactly the property that makes it an
                // outbox rather than a log written afterwards.
                transactions.executeWithoutResult { events.publishEvent(ProbeEvent(marker)) }

                // The listener is @Async, so the row appears shortly after the commit.
                val open = eventually {
                    jdbcTemplate.queryForObject(
                        """
                        select count(*) from orchestrator.event_publication
                        where completion_date is null and serialized_event like ?
                        """.trimIndent(),
                        Long::class.java, "%$marker%"
                    ) ?: 0L
                }
                open shouldBe 1L
            }
        }
    }

    /** Polls rather than sleeping a fixed span - the listener runs on another thread. */
    private fun eventually(block: () -> Long): Long {
        val deadline = System.currentTimeMillis() + 5_000
        var last = 0L
        while (System.currentTimeMillis() < deadline) {
            last = block()
            if (last > 0L) return last
            Thread.sleep(50)
        }
        return last
    }

    /** A plain data class so the registry's Jackson serializer can store and restore it. */
    data class ProbeEvent(val marker: String)

    /**
     * The listener sits on the configuration class itself rather than on a `@Bean`-declared class
     * of its own: `@ApplicationModuleListener` implies `@Async`/`@Transactional`, so Spring has to
     * proxy the bean, and Kotlin classes are final by default. The `kotlin("plugin.spring")`
     * plugin already in this build opens classes that carry a Spring annotation - which
     * `@TestConfiguration` is, and a plain class handed out by `@Bean` is not.
     */
    @TestConfiguration
    class FailingListenerConfig {

        @ApplicationModuleListener
        fun on(event: ProbeEvent) {
            // Throwing is how a listener says "not done" - the publication stays open and is
            // resubmitted. Exactly what a failing Keycloak call does in production.
            error("probe failure for ${event.marker}")
        }
    }
}

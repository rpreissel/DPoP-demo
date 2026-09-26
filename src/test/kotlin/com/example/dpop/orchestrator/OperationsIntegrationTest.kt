package com.example.dpop.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Health and metrics (docs/07-betrieb.md Abschnitt 7, review 2026-09-26, B-5): on the management
 * port, never on the public one - and, for the demo, readable through the welcome page's
 * server-info.
 */
class OperationsIntegrationTest : IntegrationTestSupport() {

    @Value("\${local.management.port}")
    private var managementPort: Int = 0

    init {
        given("the management port") {
            then("readiness depends on the database and is UP") {
                val readiness = restTemplate.getForObject("http://localhost:$managementPort/actuator/health/readiness", Map::class.java)!!
                readiness["status"] shouldBe "UP"
            }

            then("prometheus carries this project's own meters") {
                val scrape = restTemplate.getForObject("http://localhost:$managementPort/actuator/prometheus", String::class.java)!!
                scrape shouldContain "dpop_events_incomplete"
            }
        }

        given("the public port") {
            then("has no actuator - health and metrics never leave through the public route") {
                val e = shouldThrow<HttpClientErrorException> {
                    restTemplate.getForObject("http://localhost:$port/actuator/health", String::class.java)
                }
                e.statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }

        given("the welcome page's server info") {
            then("shows the same health and the project's meters") {
                @Suppress("UNCHECKED_CAST")
                val operations = restTemplate.getForObject("http://localhost:$port/orchestrator/demo/server-info", Map::class.java)!!["operations"] as Map<String, Any?>
                operations["status"] shouldBe "UP"
                @Suppress("UNCHECKED_CAST")
                val components = (operations["components"] as List<Map<String, Any?>>).associate { it["name"] to it["status"] }
                components["db"] shouldBe "UP"
                @Suppress("UNCHECKED_CAST")
                (operations["metrics"] as List<Map<String, Any?>>).map { it["name"] } shouldContain "dpop.events.incomplete"
            }
        }
    }
}

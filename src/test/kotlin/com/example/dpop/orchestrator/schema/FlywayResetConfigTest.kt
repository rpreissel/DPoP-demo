package com.example.dpop.orchestrator.schema

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import javax.sql.DataSource

/**
 * The migration strategy that deletes the H2 file on a failed migration exists in demo mode only
 * (review 2026-09-26, B-2 and T-1): outside it the file IS the production database.
 */
class FlywayResetConfigTest : BehaviorSpec({

    val runner = ApplicationContextRunner()
        .withUserConfiguration(FlywayResetConfig::class.java)
        .withBean(DataSource::class.java, { mockk<DataSource>(relaxed = true) })
        .withPropertyValues("spring.datasource.url=jdbc:h2:file:./data/never-touched")

    given("demo mode") {
        then("the resetting strategy is there") {
            runner.withPropertyValues("demo.mode=true").run { context ->
                context.getBeansOfType(FlywayMigrationStrategy::class.java).size shouldBe 1
            }
        }
    }

    given("operation - demo mode off") {
        then("it does not exist at all, so a failed migration stops the start instead of wiping the data") {
            runner.withPropertyValues("demo.mode=false").run { context ->
                context.getBeansOfType(FlywayMigrationStrategy::class.java).size shouldBe 0
            }
        }
    }
})

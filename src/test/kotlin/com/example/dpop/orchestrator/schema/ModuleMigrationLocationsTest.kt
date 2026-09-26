package com.example.dpop.orchestrator.schema

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

/** Review 2026-09-26: the demo personas are migrated in demo mode only. */
class ModuleMigrationLocationsTest : BehaviorSpec({

    fun locations(demoMode: Boolean): List<String> = ModuleMigrationLocations(demoMode).moduleLocations()

    given("demo mode") {
        then("every module folder runs, demo_seed included") {
            locations(true) shouldContain "classpath:db/migration/demo_seed"
            locations(true) shouldContain "classpath:db/migration/account"
        }
    }

    given("demo mode off") {
        then("demo_seed is left out, every other module still runs") {
            locations(false) shouldNotContain "classpath:db/migration/demo_seed"
            locations(false) shouldContain "classpath:db/migration/account"
        }
    }
})

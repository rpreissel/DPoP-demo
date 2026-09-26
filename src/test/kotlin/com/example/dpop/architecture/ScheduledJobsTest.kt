package com.example.dpop.architecture

import com.example.dpop.orchestrator.SCHEDULED_JOBS
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.scheduling.annotation.Scheduled

/**
 * Keeps [SCHEDULED_JOBS] - and with it the single-instance check and docs/07-betrieb.md
 * Abschnitt 3b - complete (review 2026-09-26, B-8): a new `@Scheduled` method that is not listed,
 * or a listed job that is gone, fails here.
 */
class ScheduledJobsTest : BehaviorSpec({
    given("the application's classes") {
        then("every class with a @Scheduled method is a declared job, and every declared job has one") {
            val scheduled = ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.example.dpop")
                .filter { cls -> cls.methods.any { it.isAnnotatedWith(Scheduled::class.java) } }
                .map { it.simpleName }
                .toSet()
            scheduled shouldBe SCHEDULED_JOBS.keys
        }
    }
})

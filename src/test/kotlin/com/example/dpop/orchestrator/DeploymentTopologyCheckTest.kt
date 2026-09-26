package com.example.dpop.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/** The single-instance claim is checked, not assumed (docs/07-betrieb.md Abschnitt 3b; review 2026-09-26, T-1). */
class DeploymentTopologyCheckTest : BehaviorSpec({

    given("a single instance") {
        then("it starts, even with the pepper left to chance") {
            DeploymentTopologyCheck(DeploymentProperties(DeploymentProperties.Instances.SINGLE), otpPepper = "").check()
        }
    }

    given("a claim of several instances") {
        then("it refuses and names what is still missing - the jobs by name, the per-process secrets") {
            val failure = shouldThrow<IllegalStateException> {
                DeploymentTopologyCheck(DeploymentProperties(DeploymentProperties.Instances.MULTIPLE), otpPepper = "").check()
            }
            failure.message!! shouldContain "otp-pepper"
            failure.message!! shouldContain "RestoreDataCodec"
            SCHEDULED_JOBS.keys.forEach { failure.message!! shouldContain it }
        }

        then("a shared pepper alone does not make it fit - the jobs still have no lock") {
            val failure = shouldThrow<IllegalStateException> {
                DeploymentTopologyCheck(DeploymentProperties(DeploymentProperties.Instances.MULTIPLE), otpPepper = "x".repeat(32)).check()
            }
            failure.message!! shouldContain "RetentionJob"
        }
    }
})

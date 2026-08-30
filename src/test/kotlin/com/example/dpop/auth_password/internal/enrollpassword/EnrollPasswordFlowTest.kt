package com.example.dpop.auth_password.internal.enrollpassword

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EnrollPasswordFlowTest : BehaviorSpec({

    given("no password submitted") {
        then("the state is unchanged") {
            EnrollPasswordFlow.decide(EnrollPasswordInput()) shouldBe EnrollPasswordDecision.Unchanged
        }
    }

    given("a password shorter than the minimum length") {
        then("it is rejected as too short") {
            EnrollPasswordFlow.decide(EnrollPasswordInput("short")) shouldBe EnrollPasswordDecision.TooShort("short")
        }
    }

    given("a password meeting the minimum length") {
        then("enrollment is requested") {
            EnrollPasswordFlow.decide(EnrollPasswordInput("correct-horse-battery")) shouldBe
                EnrollPasswordDecision.Enroll("correct-horse-battery")
        }
    }

    given("describe()") {
        then("it asks for password at step enroll, with the demo password") {
            val (step, fields) = EnrollPasswordFlow.describe()
            step shouldBe "enroll"
            fields["missingFields"] shouldBe listOf("password")
        }
    }
})

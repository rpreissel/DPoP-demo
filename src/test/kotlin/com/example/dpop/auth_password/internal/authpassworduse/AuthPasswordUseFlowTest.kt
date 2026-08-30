package com.example.dpop.auth_password.internal.authpassworduse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AuthPasswordUseFlowTest : BehaviorSpec({

    given("no password submitted") {
        then("the state is unchanged") {
            AuthPasswordUseFlow.decide(AuthPasswordUseInput()) shouldBe AuthPasswordUseDecision.Unchanged
        }
    }

    given("a password submitted") {
        then("it is named for the handler to check against the enrollment") {
            val decision = AuthPasswordUseFlow.decide(AuthPasswordUseInput("hunter2"))
            decision.shouldBeInstanceOf<AuthPasswordUseDecision.Check>()
            (decision as AuthPasswordUseDecision.Check).password shouldBe "hunter2"
        }
    }

    given("describe()") {
        then("it asks for password at step auth, with the demo password") {
            val (step, fields) = AuthPasswordUseFlow.describe()
            step shouldBe "auth"
            fields["missingFields"] shouldBe listOf("password")
        }
    }
})

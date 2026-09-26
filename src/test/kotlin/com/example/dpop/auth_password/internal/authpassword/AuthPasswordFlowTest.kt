package com.example.dpop.auth_password.internal.authpassword

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.example.dpop.tool_spi.MissingFields

class AuthPasswordFlowTest : BehaviorSpec({

    given("no password submitted") {
        then("the state is unchanged") {
            AuthPasswordFlow.decide(AuthPasswordInput()) shouldBe AuthPasswordDecision.Unchanged
        }
    }

    given("a password submitted") {
        then("it is named for the handler to check against the enrollment") {
            val decision = AuthPasswordFlow.decide(AuthPasswordInput("hunter2"))
            decision.shouldBeInstanceOf<AuthPasswordDecision.Check>()
            (decision as AuthPasswordDecision.Check).password shouldBe "hunter2"
        }
    }

    given("describe()") {
        then("it asks for password at step auth, with the demo password") {
            val (step, fields) = AuthPasswordFlow.describe()
            step shouldBe "auth"
            fields shouldBe MissingFields(listOf("password"))
        }
    }
})

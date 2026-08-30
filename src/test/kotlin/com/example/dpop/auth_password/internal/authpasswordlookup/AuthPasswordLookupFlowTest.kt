package com.example.dpop.auth_password.internal.authpasswordlookup

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthPasswordLookupFlowTest : BehaviorSpec({

    given("neither email nor password submitted") {
        then("both are reported missing") {
            AuthPasswordLookupFlow.decide(AuthPasswordLookupInput()) shouldBe
                AuthPasswordLookupDecision.Incomplete(listOf("email", "password"))
        }
    }

    given("only email submitted") {
        then("password is reported missing") {
            AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(email = "max@example.com")) shouldBe
                AuthPasswordLookupDecision.Incomplete(listOf("password"))
        }
    }

    given("only password submitted") {
        then("email is reported missing") {
            AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(password = "hunter2")) shouldBe
                AuthPasswordLookupDecision.Incomplete(listOf("email"))
        }
    }

    given("both submitted") {
        then("a check is requested with both values") {
            AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(email = "max@example.com", password = "hunter2")) shouldBe
                AuthPasswordLookupDecision.Check("max@example.com", "hunter2")
        }
    }

    given("describe()") {
        then("it reports exactly the given missing fields, with the demo values") {
            val (step, fields) = AuthPasswordLookupFlow.describe(listOf("password"))
            step shouldBe "auth"
            fields["missingFields"] shouldBe listOf("password")
        }
    }
})

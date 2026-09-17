package com.example.dpop.tool_api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class EmailTest : BehaviorSpec({

    given("ofOrNull") {
        then("normalizes to trimmed lowercase") {
            Email.ofOrNull("  Max@Example.COM ")?.value shouldBe "max@example.com"
        }
        then("rejects a value with no @ or no domain dot") {
            Email.ofOrNull("not-an-email").shouldBeNull()
            Email.ofOrNull("max@example").shouldBeNull()
            Email.ofOrNull("@example.com").shouldBeNull()
        }
        then("accepts a well-formed address") {
            Email.ofOrNull("max@example.com").shouldNotBeNull()
        }
    }

    given("of") {
        then("throws for a malformed address") {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { Email.of("not-an-email") }
        }
        then("returns the normalized value for a well-formed one") {
            Email.of("Max@Example.COM").value shouldBe "max@example.com"
        }
    }
})

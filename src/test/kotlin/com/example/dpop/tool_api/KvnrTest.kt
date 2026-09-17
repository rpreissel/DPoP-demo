package com.example.dpop.tool_api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class KvnrTest : BehaviorSpec({

    given("ofOrNull") {
        then("normalizes to trimmed uppercase") {
            Kvnr.ofOrNull(" a123456789 ")?.value shouldBe "A123456789"
        }
        then("rejects a value that isn't one letter followed by nine digits") {
            Kvnr.ofOrNull("A12345678").shouldBeNull()
            Kvnr.ofOrNull("A1234567890").shouldBeNull()
            Kvnr.ofOrNull("AB123456789").shouldBeNull()
            Kvnr.ofOrNull("1234567890").shouldBeNull()
        }
    }

    given("of") {
        then("throws for a malformed value") {
            shouldThrow<IllegalArgumentException> { Kvnr.of("not-a-kvnr") }
        }
        then("returns the normalized value for a well-formed one") {
            Kvnr.of("a123456789").value shouldBe "A123456789"
        }
    }
})

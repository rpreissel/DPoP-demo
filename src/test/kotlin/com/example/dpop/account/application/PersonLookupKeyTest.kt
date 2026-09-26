package com.example.dpop.account.application

import com.example.dpop.account.application.LookupKeyCoverageCheck
import com.example.dpop.account.application.PersonLookupKey
import com.example.dpop.account.application.PreviousLookupSecrets
import com.example.dpop.account.infrastructure.ChangeLogRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

/** Rotating the change log's search secret (review 2026-09-26, F-10). */
class PersonLookupKeyTest : BehaviorSpec({
    val born = LocalDate.parse("1985-06-15")
    val oldSecret = "a".repeat(32)
    val newSecret = "b".repeat(32)

    given("a secret rotated from id 1 to id 2, the old one kept for searching") {
        val before = PersonLookupKey(oldSecret, "1", PreviousLookupSecrets(), demoMode = false)
        val after = PersonLookupKey(newSecret, "2", PreviousLookupSecrets(mapOf("1" to oldSecret)), demoMode = false)
        val written = before.of("Müller", "Max", born)!!

        then("new keys are written with the new secret and carry its id") {
            val key = after.of("Müller", "Max", born)!!
            key.keyId shouldBe "2"
            key.value shouldNotBe written.value
        }

        then("a search still finds what the old secret wrote") {
            after.candidates("Müller", "Max", born) shouldContain written.value
            after.knownKeyIds shouldBe setOf("1", "2")
        }
    }

    given("the change log holds keys of an id no secret is configured for") {
        val repository = mockk<ChangeLogRepository> { every { lookupKeyIds() } returns setOf("1") }
        val withoutOld = PersonLookupKey(newSecret, "2", PreviousLookupSecrets(), demoMode = false)

        then("the start fails outside demo mode - those entries could no longer be found") {
            shouldThrow<IllegalStateException> { LookupKeyCoverageCheck(repository, withoutOld, demoMode = false).check() }
        }

        then("demo mode only warns") {
            LookupKeyCoverageCheck(repository, withoutOld, demoMode = true).check()
        }
    }
})

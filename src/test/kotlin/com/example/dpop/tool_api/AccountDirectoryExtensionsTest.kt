package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class AccountDirectoryExtensionsTest : BehaviorSpec({
    given("typed account-ID lookup extensions") {
        then("email delegates once to the generic anchor lookup, which owns normalization") {
            val directory = mockk<AccountDirectory>()
            every { directory.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ") } returns 7L
            directory.resolveAccountByEmail("  Max@Example.COM ") shouldBe 7L
            verify(exactly = 1) { directory.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ") }
            confirmVerified(directory)
        }

        then("person ID delegates once using its canonical decimal value") {
            val directory = mockk<AccountDirectory>()
            every { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") } returns 7L
            directory.resolveAccountByPersonId("P000000042") shouldBe 7L
            verify(exactly = 1) { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") }
            confirmVerified(directory)
        }

        then("missing local anchors remain absent") {
            val directory = mockk<AccountDirectory>()
            every { directory.resolveByAnchor(any(), any()) } returns null
            directory.resolveAccountByEmail("missing@example.com") shouldBe null
            directory.resolveAccountByPersonId("P000000042") shouldBe null
        }

        then("KVNR follows current master data to the person-ID anchor") {
            val directory = mockk<AccountDirectory>()
            val persons = mockk<PersonDirectory>()
            every { persons.findPersonIdByKvnr("A123456789") } returns "P000000042"
            every { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") } returns 7L
            directory.resolveAccountByKvnr(" a123456789 ", persons) shouldBe 7L
            verify(exactly = 1) { persons.findPersonIdByKvnr("A123456789") }
            verify(exactly = 1) { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") }
            confirmVerified(persons, directory)
        }

        then("an unknown KVNR does not fall back to local account data") {
            val directory = mockk<AccountDirectory>()
            val persons = mockk<PersonDirectory>()
            every { persons.findPersonIdByKvnr("A123456789") } returns null
            directory.resolveAccountByKvnr("A123456789", persons) shouldBe null
            verify { directory wasNot Called }
        }

        then("a known person without an account returns no account") {
            val directory = mockk<AccountDirectory>()
            val persons = mockk<PersonDirectory>()
            every { persons.findPersonIdByKvnr("A123456789") } returns "P000000042"
            every { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") } returns null
            directory.resolveAccountByKvnr("A123456789", persons) shouldBe null
        }
    }
})

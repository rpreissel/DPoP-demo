package com.example.dpop.nect_mock

import com.example.dpop.nect_mock.internal.NectCase
import com.example.dpop.nect_mock.internal.NectCaseRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * The provider's own rules, with its repository mocked: a case is redeemed exactly once, the eID
 * needs its PIN, an expired passport fails the case, and a finished case cannot be finished again.
 */
class NectIdentTest : BehaviorSpec({

    fun fixture(): NectIdent {
        val repository = mockk<NectCaseRepository>()
        val store = mutableMapOf<UUID, NectCase>()
        val saved = slot<NectCase>()
        every { repository.save(capture(saved)) } answers { store[checkNotNull(saved.captured.id)] = saved.captured; saved.captured }
        every { repository.findById(any()) } answers { Optional.ofNullable(store[firstArg<UUID>()]) }
        return NectIdent(repository)
    }

    val max = NectAttributes(name = "Muster", vorname = "Max", geburtsdatum = LocalDate.of(1985, 6, 15))

    given("an open case") {
        then("it stays open until the user finishes, and redeeming it then succeeds exactly once") {
            val nect = fixture()
            val case = nect.createCase("/app/")
            nect.redeem(case.caseId) shouldBe NectResult.Open

            nect.complete(case.caseId, NectProcedure.EID, max, pin = "123456") shouldBe "/app/?nectCaseId=${case.caseId}"

            val result = nect.redeem(case.caseId)
            result.shouldBeInstanceOf<NectResult.Identified>()
            result.procedure shouldBe NectProcedure.EID
            result.attributes shouldBe max
            nect.redeem(case.caseId).shouldBeNull()
        }

        then("a wrong eID PIN is refused and the case stays open") {
            val nect = fixture()
            val case = nect.createCase("/app/")
            shouldThrow<NectRejectedException> { nect.complete(case.caseId, NectProcedure.EID, max, pin = "000000") }
            nect.caseView(case.caseId)?.status shouldBe "OPEN"
        }

        then("an expired passport fails the case") {
            val nect = fixture()
            val case = nect.createCase("/app/")
            nect.complete(case.caseId, NectProcedure.EPASS, max.copy(expiryDate = LocalDate.now().minusDays(1)), pin = null)
            nect.redeem(case.caseId) shouldBe NectResult.Failed("Reisepass abgelaufen")
        }

        then("a cancelled case cannot be completed afterwards") {
            val nect = fixture()
            val case = nect.createCase("/app/?x=1")
            nect.cancel(case.caseId) shouldBe "/app/?x=1&nectCaseId=${case.caseId}"
            shouldThrow<NectRejectedException> { nect.complete(case.caseId, NectProcedure.EUDI, max, pin = null) }
            nect.redeem(case.caseId) shouldBe NectResult.Cancelled
        }
    }

    given("an unknown case") {
        then("redeeming answers null") {
            fixture().redeem(UUID.randomUUID()).shouldBeNull()
        }
    }
})

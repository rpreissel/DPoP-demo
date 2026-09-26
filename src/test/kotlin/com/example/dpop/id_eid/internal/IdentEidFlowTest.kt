package com.example.dpop.id_eid.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import com.example.dpop.tool_spi.MissingFields

class IdentEidFlowTest : BehaviorSpec({

    val cardFields = EidPatchFields(
        familyName = "Muster",
        givenNames = "Max",
        birthDate = LocalDate.of(1990, 1, 1),
        streetAddress = "Musterstr. 1",
        postalCode = "12345",
        locality = "Musterstadt",
        restrictedId = "T0103005K1D5S0V8T9W6UM2RTX"
    )
    val pinField = EidPatchFields(pin = IdentEidFlow.MOCK_PIN)

    given("a fresh state") {
        val state = IdentEidState()

        then("it names step card - nothing is typed before the card is read") {
            IdentEidFlow.describe(state) shouldBe ("card" to MissingFields(IdentEidFlow.CARD_FIELDS))
        }

        then("decide() reports it as incomplete") {
            IdentEidFlow.decide(state) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("card fields present, pin missing") {
        val state = IdentEidFlow.merge(IdentEidState(), cardFields)

        then("it names step pin") {
            IdentEidFlow.describe(state) shouldBe ("pin" to MissingFields(IdentEidFlow.PIN_FIELDS))
        }

        then("decide() still reports it as incomplete") {
            IdentEidFlow.decide(state) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("card fields and pin present") {
        val state = IdentEidFlow.merge(IdentEidFlow.merge(IdentEidState(), cardFields), pinField)

        then("decide() asks the handler to verify it, carrying only what the card showed") {
            val decision = IdentEidFlow.decide(state)
            decision.shouldBeInstanceOf<IdentEidDecision.Verify>()
            decision.claimed.familyName shouldBe "Muster"
            decision.claimed.givenNames shouldBe "Max"
            decision.claimed.birthDate shouldBe LocalDate.of(1990, 1, 1)
        }
    }

    given("pinMatchesMock()") {
        `when`("the correct mock PIN was hashed") {
            then("it matches") {
                val state = IdentEidFlow.merge(IdentEidState(), pinField)
                IdentEidFlow.pinMatchesMock(state.pinHash!!) shouldBe true
            }
        }

        `when`("a wrong PIN was hashed") {
            then("it does not match") {
                val state = IdentEidFlow.merge(IdentEidState(), EidPatchFields(pin = "000000"))
                IdentEidFlow.pinMatchesMock(state.pinHash!!) shouldBe false
            }
        }
    }

    given("merge()") {
        then("a later PATCH does not overwrite fields it doesn't mention") {
            val afterCard = IdentEidFlow.merge(IdentEidState(), cardFields)
            val afterPin = IdentEidFlow.merge(afterCard, pinField)
            afterPin.familyName shouldBe "Muster"
            afterPin.birthDate shouldBe LocalDate.of(1990, 1, 1)
        }
    }
})

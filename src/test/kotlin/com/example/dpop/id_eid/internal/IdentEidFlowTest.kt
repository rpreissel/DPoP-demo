package com.example.dpop.id_eid.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

class IdentEidFlowTest : BehaviorSpec({

    val cardFields = EidPatchFields(
        name = "Muster",
        vorname = "Max",
        geburtsdatum = LocalDate.of(1990, 1, 1),
        strasse = "Musterstr.",
        hausnummer = "1",
        plz = "12345",
        ort = "Musterstadt"
    )
    val pinField = EidPatchFields(pin = IdentEidFlow.MOCK_PIN)

    given("a fresh state") {
        val state = IdentEidState()

        then("it names step card - nothing is typed before the card is read") {
            IdentEidFlow.describe(state) shouldBe ("card" to mapOf("missingFields" to IdentEidFlow.CARD_FIELDS))
        }

        then("decide() reports it as incomplete") {
            IdentEidFlow.decide(state) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("card fields present, pin missing") {
        val state = IdentEidFlow.merge(IdentEidState(), cardFields)

        then("it names step pin") {
            IdentEidFlow.describe(state) shouldBe ("pin" to mapOf("missingFields" to IdentEidFlow.PIN_FIELDS))
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
            decision.claimed.name shouldBe "Muster"
            decision.claimed.vorname shouldBe "Max"
            decision.claimed.geburtsdatum shouldBe LocalDate.of(1990, 1, 1)
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
            afterPin.name shouldBe "Muster"
            afterPin.geburtsdatum shouldBe LocalDate.of(1990, 1, 1)
        }
    }
})

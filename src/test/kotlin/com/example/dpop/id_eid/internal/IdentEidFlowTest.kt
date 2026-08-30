package com.example.dpop.id_eid.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

class IdentEidFlowTest : BehaviorSpec({

    val lookupFields = EidPatchFields(kvnr = "A123456789", name = "Muster", vorname = "Max")
    val cardFields = EidPatchFields(
        geburtsdatum = LocalDate.of(1990, 1, 1),
        strasse = "Musterstr.",
        hausnummer = "1",
        plz = "12345",
        ort = "Musterstadt"
    )
    val pinField = EidPatchFields(pin = IdentEidFlow.MOCK_PIN)

    given("a fresh state") {
        val state = IdentEidState()

        then("it names step input") {
            IdentEidFlow.describe(state) shouldBe ("input" to mapOf("missingFields" to IdentEidFlow.LOOKUP_FIELDS))
        }

        then("decide() reports it as incomplete") {
            IdentEidFlow.decide(state) shouldBe IdentEidDecision.Incomplete
        }
    }

    given("lookup fields present, card fields missing") {
        val state = IdentEidFlow.merge(IdentEidState(), lookupFields, personId = null)

        then("it names step card") {
            IdentEidFlow.describe(state) shouldBe ("card" to mapOf("missingFields" to IdentEidFlow.CARD_FIELDS))
        }
    }

    given("lookup and card fields present, pin missing") {
        val state = IdentEidFlow.merge(IdentEidFlow.merge(IdentEidState(), lookupFields, personId = null), cardFields, personId = null)

        then("it names step pin") {
            IdentEidFlow.describe(state) shouldBe ("pin" to mapOf("missingFields" to IdentEidFlow.PIN_FIELDS))
        }
    }

    given("all fields present but no person resolved") {
        var state = IdentEidState()
        state = IdentEidFlow.merge(state, lookupFields, personId = null)
        state = IdentEidFlow.merge(state, cardFields, personId = null)
        state = IdentEidFlow.merge(state, pinField, personId = null)

        then("decide() reports the person as not found") {
            IdentEidFlow.decide(state) shouldBe IdentEidDecision.PersonNotFound
        }
    }

    given("all fields present and a person resolved") {
        var state = IdentEidState()
        state = IdentEidFlow.merge(state, lookupFields, personId = 5L)
        state = IdentEidFlow.merge(state, cardFields, personId = null)
        state = IdentEidFlow.merge(state, pinField, personId = null)

        then("decide() asks the handler to verify it, carrying the claimed identity") {
            val decision = IdentEidFlow.decide(state)
            decision.shouldBeInstanceOf<IdentEidDecision.Verify>()
            (decision as IdentEidDecision.Verify).personId shouldBe 5L
            decision.claimed.geburtsdatum shouldBe LocalDate.of(1990, 1, 1)
        }
    }

    given("pinMatchesMock()") {
        `when`("the correct mock PIN was hashed") {
            then("it matches") {
                val state = IdentEidFlow.merge(IdentEidState(), pinField, personId = null)
                IdentEidFlow.pinMatchesMock(state.pinHash!!) shouldBe true
            }
        }

        `when`("a wrong PIN was hashed") {
            then("it does not match") {
                val state = IdentEidFlow.merge(IdentEidState(), EidPatchFields(pin = "000000"), personId = null)
                IdentEidFlow.pinMatchesMock(state.pinHash!!) shouldBe false
            }
        }
    }

    given("merge()") {
        then("a later PATCH does not overwrite fields it doesn't mention") {
            val afterLookup = IdentEidFlow.merge(IdentEidState(), lookupFields, personId = 5L)
            val afterCard = IdentEidFlow.merge(afterLookup, cardFields, personId = null)
            afterCard.kvnr shouldBe "A123456789"
            afterCard.personId shouldBe 5L
        }
    }
})

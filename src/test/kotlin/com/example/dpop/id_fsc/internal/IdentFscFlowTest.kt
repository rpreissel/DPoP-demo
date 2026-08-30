package com.example.dpop.id_fsc.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

class IdentFscFlowTest : BehaviorSpec({

    given("a fresh state") {
        val state = IdentFscState()

        `when`("nothing was submitted yet") {
            then("only kvnr/name/vorname are reported missing - fsc is staged, not requested yet") {
                IdentFscFlow.missingFields(state) shouldBe listOf("kvnr", "name", "vorname")
            }

            then("decide() reports it as incomplete") {
                IdentFscFlow.decide(state) shouldBe IdentFscDecision.Incomplete
            }
        }

        `when`("kvnr/name/vorname are submitted, resolving no person") {
            val merged = IdentFscFlow.merge(state, IdentFscInput(kvnr = "A123456789", name = "Muster", vorname = "Max", personId = null))

            then("fsc is now requested") {
                IdentFscFlow.missingFields(merged) shouldBe listOf("fsc")
            }
        }
    }

    given("kvnr/name/vorname/fsc all present, but the KVNR resolved no person") {
        val state = IdentFscState(kvnr = "A123456789", name = "Muster", vorname = "Max", fscHash = "somehash", personId = null)

        then("decide() reports the person as not found") {
            IdentFscFlow.decide(state) shouldBe IdentFscDecision.PersonNotFound
        }
    }

    given("kvnr/name/vorname/fsc all present and a person resolved") {
        val state = IdentFscState(kvnr = "A123456789", name = "Muster", vorname = "Max", fscHash = "somehash", personId = 5L)

        then("decide() asks the handler to verify it against the DB") {
            val decision = IdentFscFlow.decide(state)
            decision.shouldBeInstanceOf<IdentFscDecision.Verify>()
            (decision as IdentFscDecision.Verify).personId shouldBe 5L
            decision.fscHash shouldBe "somehash"
        }
    }

    given("merge()") {
        val state = IdentFscState(kvnr = "A123456789", name = "Muster")

        `when`("a later PATCH corrects vorname and never touches kvnr/name") {
            then("kvnr/name survive, vorname is added") {
                val merged = IdentFscFlow.merge(state, IdentFscInput(vorname = "Max"))
                merged shouldBe state.copy(vorname = "Max")
            }
        }

        `when`("fsc is submitted") {
            then("it is hashed deterministically, never stored in the clear") {
                val merged = IdentFscFlow.merge(state, IdentFscInput(fsc = "VALIDCODE"))
                merged.fscHash shouldNotBe "VALIDCODE"
                merged.fscHash shouldBe IdentFscFlow.merge(IdentFscState(), IdentFscInput(fsc = "VALIDCODE")).fscHash
            }
        }
    }

    given("decideVerification()") {
        `when`("throttled, name mismatched, or the code is invalid") {
            then("it rejects in every case") {
                IdentFscFlow.decideVerification(throttled = true, nameMatches = true, codeValid = true) shouldBe IdentFscVerifyDecision.Rejected
                IdentFscFlow.decideVerification(throttled = false, nameMatches = false, codeValid = true) shouldBe IdentFscVerifyDecision.Rejected
                IdentFscFlow.decideVerification(throttled = false, nameMatches = true, codeValid = false) shouldBe IdentFscVerifyDecision.Rejected
            }
        }

        `when`("not throttled, name matches, and the code is valid") {
            then("it completes") {
                IdentFscFlow.decideVerification(throttled = false, nameMatches = true, codeValid = true) shouldBe IdentFscVerifyDecision.Complete
            }
        }
    }

    given("describe()") {
        then("it always names step input, regardless of which fields are missing") {
            IdentFscFlow.describe(IdentFscState()).first shouldBe "input"
            IdentFscFlow.describe(IdentFscState(kvnr = "A123456789", name = "Muster", vorname = "Max")).first shouldBe "input"
        }
    }
})

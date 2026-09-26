package com.example.dpop.id_fsc.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate

/** Stands in for the register's digest (ActivationCodes.digest) - the flow only stages what it gets. */
private val DIGEST: (String) -> String = { "digest:$it" }

private val BIRTHDATE = LocalDate.of(1985, 6, 15)

private val PERSONAL_DETAILS = IdentFscInput(kvnr = "A123456789", familyName = "Muster", givenNames = "Max", birthDate = BIRTHDATE, personId = "P000000005")

class IdentFscFlowTest : BehaviorSpec({

    given("a fresh state") {
        val state = IdentFscState()

        `when`("nothing was submitted yet") {
            then("only kvnr/familyName/givenNames/birthDate are reported missing - fsc is staged, not requested yet") {
                IdentFscFlow.missingFields(state) shouldBe listOf("kvnr", "familyName", "givenNames", "birthDate")
            }

            then("decide() reports it as incomplete") {
                IdentFscFlow.decide(state, IdentFscInput()) shouldBe IdentFscDecision.Incomplete
            }
        }

        `when`("the personal data is submitted, but the KVNR resolved no person") {
            val input = PERSONAL_DETAILS.copy(personId = null)
            val merged = IdentFscFlow.merge(state, input, DIGEST)

            then("decide() reports the person as not found - before any code is asked for") {
                IdentFscFlow.decide(merged, input) shouldBe IdentFscDecision.PersonNotFound
            }
        }

        `when`("the personal data is submitted and resolved a person") {
            val merged = IdentFscFlow.merge(state, PERSONAL_DETAILS, DIGEST)

            then("decide() asks for the personal data to be checked right away") {
                IdentFscFlow.decide(merged, PERSONAL_DETAILS) shouldBe
                    IdentFscDecision.VerifyPersonalDetails("P000000005", "Muster", "Max", BIRTHDATE)
            }

            then("fsc is what is still missing") {
                IdentFscFlow.missingFields(merged) shouldBe listOf("fsc")
            }
        }
    }

    given("verified personal data") {
        val state = IdentFscFlow.merge(IdentFscState(), PERSONAL_DETAILS, DIGEST)

        `when`("only the code is submitted") {
            val input = IdentFscInput(fsc = "VALIDCODE")
            val merged = IdentFscFlow.merge(state, input, DIGEST)

            then("decide() checks only the code") {
                IdentFscFlow.decide(merged, input) shouldBe IdentFscDecision.VerifyCode("P000000005", checkNotNull(merged.fscHash))
            }
        }

        `when`("a personal field is corrected") {
            val input = IdentFscInput(birthDate = BIRTHDATE.plusDays(1))

            then("decide() checks the personal data again") {
                IdentFscFlow.decide(IdentFscFlow.merge(state, input, DIGEST), input) shouldBe
                    IdentFscDecision.VerifyPersonalDetails("P000000005", "Muster", "Max", BIRTHDATE.plusDays(1))
            }
        }

        `when`("the code is rejected") {
            then("only fsc is asked for again") {
                val withCode = IdentFscFlow.merge(state, IdentFscInput(fsc = "WRONGCODE"), DIGEST)
                IdentFscFlow.missingFields(IdentFscFlow.rejectCode(withCode)) shouldBe listOf("fsc")
            }
        }

        `when`("the personal data is rejected") {
            then("all of it is asked for again") {
                IdentFscFlow.missingFields(IdentFscFlow.rejectPersonalDetails()) shouldBe listOf("kvnr", "familyName", "givenNames", "birthDate")
            }
        }
    }

    given("the two identifiers, KVNR and Partnernummer (ADR-34)") {
        val withKvnr = IdentFscFlow.merge(IdentFscState(), PERSONAL_DETAILS, DIGEST)

        then("a Partnernummer replaces the KVNR, and its person with it") {
            val merged = IdentFscFlow.merge(withKvnr, IdentFscInput(partnernr = "P000000004", personId = "P000000004"), DIGEST)
            merged.kvnr shouldBe null
            merged.partnernr shouldBe "P000000004"
            merged.personId shouldBe "P000000004"
        }

        then("a KVNR replaces the Partnernummer") {
            val partner = IdentFscFlow.merge(IdentFscState(), IdentFscInput(partnernr = "P000000004", personId = "P000000004"), DIGEST)
            val merged = IdentFscFlow.merge(partner, IdentFscInput(kvnr = "A123456789", personId = "P000000005"), DIGEST)
            merged.kvnr shouldBe "A123456789"
            merged.partnernr shouldBe null
            merged.personId shouldBe "P000000005"
        }

        then("brought together, the KVNR wins") {
            val merged = IdentFscFlow.merge(IdentFscState(), IdentFscInput(kvnr = "A123456789", partnernr = "P000000004", personId = "P000000005"), DIGEST)
            merged.kvnr shouldBe "A123456789"
            merged.partnernr shouldBe null
        }

        then("with a Partnernummer, kvnr is not reported missing") {
            val partner = IdentFscFlow.merge(
                IdentFscState(),
                IdentFscInput(partnernr = "P000000004", familyName = "Schulz", givenNames = "Paula", birthDate = BIRTHDATE, personId = "P000000004")
            , DIGEST)
            IdentFscFlow.missingFields(partner) shouldBe listOf("fsc")
            IdentFscFlow.missingFields(IdentFscFlow.merge(IdentFscState(), IdentFscInput(partnernr = "P000000004"), DIGEST)) shouldBe
                listOf("familyName", "givenNames", "birthDate")
        }
    }

    given("merge()") {
        val state = IdentFscState(kvnr = "A123456789", familyName = "Muster", personId = "P000000005")

        `when`("a later PATCH corrects givenNames and never touches kvnr/name") {
            then("kvnr/name and the person survive, givenNames is added") {
                val merged = IdentFscFlow.merge(state, IdentFscInput(givenNames = "Max"), DIGEST)
                merged shouldBe state.copy(givenNames = "Max")
            }
        }

        `when`("a later PATCH sends a KVNR that resolves no one") {
            then("the previous KVNR's person does not survive") {
                IdentFscFlow.merge(state, IdentFscInput(kvnr = "Z999999999", personId = null), DIGEST).personId shouldBe null
            }
        }

        `when`("fsc is submitted") {
            then("it is hashed deterministically, never stored in the clear") {
                val merged = IdentFscFlow.merge(state, IdentFscInput(fsc = "VALIDCODE"), DIGEST)
                merged.fscHash shouldNotBe "VALIDCODE"
                merged.fscHash shouldBe IdentFscFlow.merge(IdentFscState(), IdentFscInput(fsc = "VALIDCODE"), DIGEST).fscHash
            }
        }
    }

    given("describe()") {
        then("it always names step input, regardless of which fields are missing") {
            IdentFscFlow.describe(IdentFscState()).first shouldBe "input"
            IdentFscFlow.describe(IdentFscState(kvnr = "A123456789", familyName = "Muster", givenNames = "Max", birthDate = BIRTHDATE)).first shouldBe "input"
        }
    }
})

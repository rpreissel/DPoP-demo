package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit test for the anchor-role vocabulary (docs/ideen/claims-modell-und-vertrauensanker.md):
 * exactly one normalization rule per anchor kind, applied identically on write and lookup,
 * and the claim-attribute -> anchor-role mapping - only KVNR and EMAIL are anchors today,
 * `phone_number` is the documented next case and stays unmapped until it grows one.
 */
class AnchorTypeTest : BehaviorSpec({

    given("an anchor type's identity") {
        `when`("asking for its wire name") {
            then("it matches the claim attribute wire name it maps from") {
                AnchorType.Kvnr.wireName shouldBe "kvnr"
                AnchorType.Email.wireName shouldBe "email"
            }
        }
    }

    given("normalization") {
        `when`("normalizing an email") {
            then("it folds to trimmed lowercase") {
                AnchorType.Email.normalize("  Max@Example.COM ") shouldBe "max@example.com"
            }
        }
        `when`("normalizing a kvnr") {
            then("it folds to trimmed uppercase") {
                AnchorType.Kvnr.normalize(" a123456789 ") shouldBe "A123456789"
            }
        }
    }

    given("the attribute type mapping") {
        `when`("asking which attributes carry the anchor role") {
            then("only kvnr and email do today") {
                AnchorType.of(AttributeType.KVNR) shouldBe AnchorType.Kvnr
                AnchorType.of(AttributeType.EMAIL) shouldBe AnchorType.Email
                AnchorType.of(AttributeType.PHONE_NUMBER).shouldBeNull()
                AnchorType.of(AttributeType.PERSON_ID).shouldBeNull()
                AnchorType.of(AttributeType.NAME).shouldBeNull()
                AnchorType.of(AttributeType.VORNAME).shouldBeNull()
                AnchorType.of(AttributeType.GEBURTSDATUM).shouldBeNull()
            }
        }
    }
})

package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit test for the anchor-role vocabulary (docs/ideen/account-attribute-und-trust-
 * vereinheitlichen.md): exactly one normalization rule per anchor kind, applied identically on
 * write and lookup, and the attribute -> anchor-binding-strength mapping - PERSON_ID and
 * EMAIL are anchors today, `phone_number` is the documented next case and stays unmapped until
 * it grows one.
 */
class AttributeRulesTest : BehaviorSpec({

    given("anchorBindingStrength") {
        then("PERSON_ID outranks the other anchors") {
            AttributeType.PERSON_ID.anchorBindingStrength shouldBe 3
        }
        then("EMAIL is weaker than PERSON_ID") {
            AttributeType.EMAIL.anchorBindingStrength shouldBe 2
        }
        then("non-anchor attributes are null") {
            AttributeType.KVNR.anchorBindingStrength.shouldBeNull()
            AttributeType.PHONE_NUMBER.anchorBindingStrength.shouldBeNull()
            AttributeType.NAME.anchorBindingStrength.shouldBeNull()
            AttributeType.VORNAME.anchorBindingStrength.shouldBeNull()
            AttributeType.GEBURTSDATUM.anchorBindingStrength.shouldBeNull()
        }
    }

    given("normalizeAnchorValue") {
        `when`("normalizing an email") {
            then("it folds to trimmed lowercase") {
                AttributeType.EMAIL.normalizeAnchorValue("  Max@Example.COM ") shouldBe "max@example.com"
            }
            then("a malformed value fails explicitly (Email.of)") {
                shouldThrow<IllegalArgumentException> { AttributeType.EMAIL.normalizeAnchorValue("not-an-email") }
            }
        }
        `when`("attempting a local kvnr anchor lookup") {
            then("it refuses because KVNR belongs to the live master data") {
                shouldThrow<IllegalStateException> { AttributeType.KVNR.normalizeAnchorValue(" a123456789 ") }
            }
            then("a malformed value fails explicitly (Kvnr.of)") {
                shouldThrow<IllegalArgumentException> { AttributeType.KVNR.normalizeAnchorValue("not-a-kvnr") }
            }
        }
        `when`("normalizing a personId") {
            then("it folds to the canonical decimal Long representation") {
                AttributeType.PERSON_ID.normalizeAnchorValue(" 007 ") shouldBe "7"
            }
            then("an invalid value fails explicitly instead of falling back to weaker matching") {
                shouldThrow<NumberFormatException> { AttributeType.PERSON_ID.normalizeAnchorValue("not-a-number") }
            }
        }
        `when`("normalizing a non-anchor attribute") {
            then("it fails explicitly instead of silently returning the raw value") {
                shouldThrow<IllegalStateException> { AttributeType.NAME.normalizeAnchorValue("Muster") }
            }
        }
    }

    given("allowsAnchorReplacement") {
        then("PERSON_ID is immutable after first binding") {
            AttributeType.PERSON_ID.allowsAnchorReplacement shouldBe false
        }
        then("EMAIL is re-provable and therefore changeable") {
            AttributeType.EMAIL.allowsAnchorReplacement shouldBe true
        }
        then("KVNR belongs to ext_stammdaten and rejects local replacement") {
            shouldThrow<IllegalStateException> { AttributeType.KVNR.allowsAnchorReplacement }
        }
        then("a non-anchor attribute rejects the question explicitly") {
            shouldThrow<IllegalStateException> { AttributeType.NAME.allowsAnchorReplacement }
        }
    }
})

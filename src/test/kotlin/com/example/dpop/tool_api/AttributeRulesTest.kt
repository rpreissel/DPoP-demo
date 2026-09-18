package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AcrLevel
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

    given("authority") {
        then("the locally anchored attributes are exactly PERSON_ID and EMAIL") {
            AttributeType.entries.filter { it.authority == AttributeAuthority.LOCAL_ANCHOR } shouldBe
                listOf(AttributeType.PERSON_ID, AttributeType.EMAIL)
        }
        then("master data owns the identifying attributes it is the register for") {
            AttributeType.entries.filter { it.authority == AttributeAuthority.EXT_STAMMDATEN } shouldBe
                listOf(AttributeType.KVNR, AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM)
        }
        then("a method module owns what it enrolled itself") {
            AttributeType.PHONE_NUMBER.authority shouldBe AttributeAuthority.METHOD_MODULE
        }
        // The one rule tying the two properties together: without it, an attribute could claim an
        // anchor rank while declaring its truth to live somewhere else, and nothing would notice.
        then("LOCAL_ANCHOR holds for exactly the attributes with an anchor binding strength") {
            AttributeType.entries.forEach { type ->
                (type.authority == AttributeAuthority.LOCAL_ANCHOR) shouldBe (type.anchorBindingStrength != null)
            }
        }
    }

    given("anchorAcrFloor") {
        then("an anchor may be established at what a registration can actually prove") {
            AttributeType.EMAIL.anchorAcrFloor?.establish shouldBe AcrLevel.LOA1
            AttributeType.PERSON_ID.anchorAcrFloor?.establish shouldBe AcrLevel.LOA2
        }
        // Replacing re-points an account that other people's lookups already resolve through -
        // the write worth protecting, unlike the first binding.
        then("replacing one costs loa2 regardless of what establishing it cost") {
            AttributeType.EMAIL.anchorAcrFloor?.replace shouldBe AcrLevel.LOA2
            AttributeType.PERSON_ID.anchorAcrFloor?.replace shouldBe AcrLevel.LOA2
        }
        then("a floor exists for exactly the locally anchored attributes") {
            AttributeType.entries.forEach { type ->
                (type.anchorAcrFloor != null) shouldBe (type.authority == AttributeAuthority.LOCAL_ANCHOR)
            }
        }
    }

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

package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit test for the anchor-role vocabulary (docs/ideen/account-attribute-und-trust-
 * vereinheitlichen.md): exactly one rule per attribute type, applied identically on write and
 * lookup - PERSON_ID and EMAIL are anchors today, `phone_number` is the documented next case and
 * stays unmapped until it grows one.
 */
class AttributeRulesTest : BehaviorSpec({

    given("rule") {
        then("PERSON_ID is a LOCAL_ANCHOR, established and replaced at loa2, immutable") {
            AttributeType.PERSON_ID.rule shouldBe AttributeRule(
                authority = AttributeAuthority.LOCAL_ANCHOR,
                anchor = AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false)
            )
        }
        then("EMAIL is a LOCAL_ANCHOR, established at loa1 but replaced only at loa2, replaceable") {
            AttributeType.EMAIL.rule shouldBe AttributeRule(
                authority = AttributeAuthority.LOCAL_ANCHOR,
                anchor = AnchorRule(AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true)
            )
        }
        then("the locally anchored attributes are exactly PERSON_ID and EMAIL") {
            AttributeType.entries.filter { it.rule.authority == AttributeAuthority.LOCAL_ANCHOR } shouldBe
                listOf(AttributeType.PERSON_ID, AttributeType.EMAIL)
        }
        then("master data owns the identifying attributes it is the register for") {
            AttributeType.entries.filter { it.rule.authority == AttributeAuthority.EXT_STAMMDATEN } shouldBe
                listOf(
                    AttributeType.KVNR, AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM,
                    AttributeType.STRASSE, AttributeType.HAUSNUMMER, AttributeType.PLZ, AttributeType.ORT
                )
        }
        then("a method module owns what it enrolled itself") {
            AttributeType.PHONE_NUMBER.rule.authority shouldBe AttributeAuthority.METHOD_MODULE
        }
        then("only LOCAL_ANCHOR types carry an AnchorRule") {
            AttributeType.entries.forEach { type ->
                (type.rule.authority == AttributeAuthority.LOCAL_ANCHOR) shouldBe (type.rule.anchor != null)
            }
        }
    }

    given("AnchorRule.bindingStrength") {
        then("PERSON_ID is immutable, so it binds most strongly") {
            AttributeType.PERSON_ID.rule.anchor?.bindingStrength shouldBe BindingStrength.IMMUTABLE_ANCHOR
        }
        then("EMAIL is replaceable, so it binds more weakly") {
            AttributeType.EMAIL.rule.anchor?.bindingStrength shouldBe BindingStrength.REPLACEABLE_ANCHOR
        }
        then("a non-anchor attribute has no anchor rule at all") {
            AttributeType.KVNR.rule.anchor.shouldBeNull()
            AttributeType.PHONE_NUMBER.rule.anchor.shouldBeNull()
            AttributeType.NAME.rule.anchor.shouldBeNull()
            AttributeType.VORNAME.rule.anchor.shouldBeNull()
            AttributeType.GEBURTSDATUM.rule.anchor.shouldBeNull()
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

    given("AnchorRule.allowsReplacement") {
        then("PERSON_ID is immutable after first binding") {
            AttributeType.PERSON_ID.rule.anchor?.allowsReplacement shouldBe false
        }
        then("EMAIL is re-provable and therefore changeable") {
            AttributeType.EMAIL.rule.anchor?.allowsReplacement shouldBe true
        }
    }
})

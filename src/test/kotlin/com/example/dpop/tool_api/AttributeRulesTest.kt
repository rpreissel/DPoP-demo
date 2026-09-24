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
 * lookup - PERSON_ID, EID_RESTRICTED_ID (ADR-19) and EMAIL are anchors today, `phone_number`
 * is the documented next case and stays unmapped until it grows one.
 */
class AttributeRulesTest : BehaviorSpec({

    given("authority") {
        then("PERSON_ID is locally owned, established and replaced at loa2, immutable") {
            AttributeType.PERSON_ID.authority shouldBe AttributeAuthority.Local(
                AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false)
            )
        }
        then("EMAIL is locally owned, established at loa1 but replaced only at loa2, replaceable") {
            AttributeType.EMAIL.authority shouldBe AttributeAuthority.Local(
                AnchorRule(AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true)
            )
        }
        then("EID_RESTRICTED_ID is locally owned, established and replaced at loa2, replaceable (ADR-19)") {
            AttributeType.EID_RESTRICTED_ID.authority shouldBe AttributeAuthority.Local(
                AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true)
            )
        }
        then("the locally anchored attributes are exactly PERSON_ID, VERSNR, the two card pseudonyms and EMAIL") {
            AttributeType.entries.filter { it.isLocalAnchor } shouldBe
                listOf(
                    AttributeType.PERSON_ID, AttributeType.VERSNR, AttributeType.EID_RESTRICTED_ID,
                    AttributeType.NECT_RESTRICTED_ID, AttributeType.EMAIL
                )
        }
        then("the pseudonym Nect reads is anchored like our own, but as a separate anchor") {
            AttributeType.NECT_RESTRICTED_ID.authority shouldBe AttributeType.EID_RESTRICTED_ID.authority
        }
        then("master data owns the identifying attributes it is the register for") {
            AttributeType.entries.filter { it.authority == AttributeAuthority.PersonDirectory } shouldBe
                listOf(
                    AttributeType.KVNR, AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM,
                    AttributeType.STRASSE, AttributeType.PLZ, AttributeType.ORT
                )
        }
        then("a method module owns what it enrolled itself") {
            AttributeType.PHONE_NUMBER.authority shouldBe AttributeAuthority.MethodModule
        }
        // No test that "only local types carry an AnchorRule": AttributeAuthority.Local is the only
        // variant that has one, so the unpaired state cannot be constructed in the first place.
    }

    given("AnchorRule.bindingStrength") {
        then("PERSON_ID is immutable, so it binds most strongly") {
            AttributeType.PERSON_ID.anchorRule?.bindingStrength shouldBe BindingStrength.IMMUTABLE_ANCHOR
        }
        then("EMAIL is replaceable, so it binds more weakly") {
            AttributeType.EMAIL.anchorRule?.bindingStrength shouldBe BindingStrength.REPLACEABLE_ANCHOR
        }
        then("EID_RESTRICTED_ID is replaceable - a new card brings a new value, never another person") {
            AttributeType.EID_RESTRICTED_ID.anchorRule?.bindingStrength shouldBe BindingStrength.REPLACEABLE_ANCHOR
        }
        then("a non-anchor attribute has no anchor rule at all") {
            AttributeType.KVNR.anchorRule.shouldBeNull()
            AttributeType.PHONE_NUMBER.anchorRule.shouldBeNull()
            AttributeType.NAME.anchorRule.shouldBeNull()
            AttributeType.VORNAME.anchorRule.shouldBeNull()
            AttributeType.GEBURTSDATUM.anchorRule.shouldBeNull()
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
            then("it folds to the canonical Partnernummer - trimmed, uppercase") {
                AttributeType.PERSON_ID.normalizeAnchorValue(" p000000007 ") shouldBe "P000000007"
            }
            then("an invalid value fails explicitly instead of falling back to weaker matching") {
                shouldThrow<IllegalArgumentException> { AttributeType.PERSON_ID.normalizeAnchorValue("7") }
            }
        }
        `when`("normalizing a restricted id") {
            then("it only trims - the card's byte string is the canonical form") {
                AttributeType.EID_RESTRICTED_ID.normalizeAnchorValue("  T0103005K1D5S0V8T9W6UM2RTX ") shouldBe
                    "T0103005K1D5S0V8T9W6UM2RTX"
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
            AttributeType.PERSON_ID.anchorRule?.allowsReplacement shouldBe false
        }
        then("EMAIL is re-provable and therefore changeable") {
            AttributeType.EMAIL.anchorRule?.allowsReplacement shouldBe true
        }
    }
})

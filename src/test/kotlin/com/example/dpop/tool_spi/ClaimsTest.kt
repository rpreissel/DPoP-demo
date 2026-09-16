package com.example.dpop.tool_spi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Pins the claims vocabulary 4vd.3 added to tool_spi: the closed [AttributeType] taxonomy, the
 * [ClaimSource] value class with its [TrustLevel]s, the [Claim]/[ClaimRequirement] shapes,
 * and the [ClaimDeclaration] offer side with its [assertClaimsCovered] contract check. The
 * policy-side gate (orchestrator's `requiresSatisfied`) is tested in its own module; this file
 * pins the SPI contract itself.
 */
class ClaimsTest : BehaviorSpec({
    given("AttributeType") {
        then("wire names are stable - they become account_attribute.attribute_type values") {
            AttributeType.PERSON_ID.wireName shouldBe "person_id"
            AttributeType.KVNR.wireName shouldBe "kvnr"
            AttributeType.NAME.wireName shouldBe "name"
            AttributeType.VORNAME.wireName shouldBe "vorname"
            AttributeType.GEBURTSDATUM.wireName shouldBe "geburtsdatum"
            AttributeType.EMAIL.wireName shouldBe "email"
            AttributeType.PHONE_NUMBER.wireName shouldBe "phone_number"
        }
    }

    given("ClaimSource") {
        then("the two named constants carry their wire values") {
            ClaimSource.EXT_STAMMDATEN.value shouldBe "ext_stammdaten"
            ClaimSource.SELF_REPORTED.value shouldBe "self-reported"
        }
        then("of() names the proving tool by its toolId") {
            ClaimSource.of(ToolId("ident-eid")).value shouldBe "ident-eid"
        }
    }

    given("TrustLevel") {
        then("rank encodes the precedence order: Stammdaten > Proven > Self-reported") {
            (TrustLevel.STAMMDATEN.rank > TrustLevel.PROVEN.rank) shouldBe true
            (TrustLevel.PROVEN.rank > TrustLevel.SELF_REPORTED.rank) shouldBe true
        }
        then("ClaimSource.trustLevel maps every source kind to its level") {
            ClaimSource.EXT_STAMMDATEN.trustLevel shouldBe TrustLevel.STAMMDATEN
            ClaimSource.SELF_REPORTED.trustLevel shouldBe TrustLevel.SELF_REPORTED
            ClaimSource.of(ToolId("ident-eid")).trustLevel shouldBe TrustLevel.PROVEN
        }
    }

    given("Claim") {
        val claim = Claim(
            attributeType = AttributeType.KVNR,
            value = "A123456789",
            source = ClaimSource.EXT_STAMMDATEN,
            establishedLoa = AcrLevel.LOA2
        )
        then("carries value, provenance and assurance") {
            claim.attributeType shouldBe AttributeType.KVNR
            claim.value shouldBe "A123456789"
            claim.source shouldBe ClaimSource.EXT_STAMMDATEN
            claim.establishedLoa shouldBe AcrLevel.LOA2
        }
        then("establishedLoa defaults to null") {
            Claim(AttributeType.EMAIL, "a@b.de", ClaimSource.of(ToolId("enroll-email"))).establishedLoa shouldBe null
        }
    }

    given("ClaimRequirement") {
        then("mirrors a claim's attribute type with a minimum trust level") {
            val requirement = ClaimRequirement(AttributeType.EMAIL, TrustLevel.PROVEN)
            requirement.attributeType shouldBe AttributeType.EMAIL
            requirement.minTrustLevel shouldBe TrustLevel.PROVEN
        }
    }

    given("ClaimDeclaration") {
        then("declares an attribute type with the source a run asserts it with") {
            val declaration = ClaimDeclaration(AttributeType.KVNR, ClaimSource.EXT_STAMMDATEN)
            declaration.attributeType shouldBe AttributeType.KVNR
            declaration.source shouldBe ClaimSource.EXT_STAMMDATEN
        }
    }

    given("assertClaimsCovered") {
        val descriptor = object : ToolDescriptor {
            override val toolId = ToolId("test-ident")
            override val method = "test"
            override val role = MethodRole.IDENTIFICATION
            override val factorTypes = setOf(FactorType.POSSESSION)
            override val maxAcr = AcrLevel.LOA2
            override val claims = setOf(
                ClaimDeclaration(AttributeType.KVNR, ClaimSource.EXT_STAMMDATEN),
                ClaimDeclaration(AttributeType.EMAIL, ClaimSource.of(toolId))
            )
        }
        then("accepts reported claims that match the declaration") {
            assertClaimsCovered(
                descriptor,
                listOf(
                    Claim(AttributeType.KVNR, "A123456789", ClaimSource.EXT_STAMMDATEN, AcrLevel.LOA2),
                    Claim(AttributeType.EMAIL, "a@b.de", ClaimSource.of(descriptor.toolId))
                )
            )
        }
        then("accepts an empty report") {
            assertClaimsCovered(descriptor, emptyList())
        }
        then("rejects an undeclared attribute type") {
            shouldThrow<IllegalStateException> {
                assertClaimsCovered(descriptor, listOf(Claim(AttributeType.NAME, "Muster", ClaimSource.EXT_STAMMDATEN)))
            }.message shouldContain "declares none"
        }
        then("rejects a claim source that differs from the declaration") {
            shouldThrow<IllegalStateException> {
                assertClaimsCovered(descriptor, listOf(Claim(AttributeType.KVNR, "A123456789", ClaimSource.of(descriptor.toolId))))
            }.message shouldContain "but declares"
        }
        then("rejects more than one claim for the same attribute type") {
            shouldThrow<IllegalStateException> {
                assertClaimsCovered(
                    descriptor,
                    listOf(
                        Claim(AttributeType.KVNR, "A123456789", ClaimSource.EXT_STAMMDATEN),
                        Claim(AttributeType.KVNR, "A987654321", ClaimSource.EXT_STAMMDATEN)
                    )
                )
            }.message shouldContain "more than one claim"
        }
    }
})

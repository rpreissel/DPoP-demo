package com.example.dpop.tool_spi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Pins the claims vocabulary 4vd.3 added to tool_spi: the closed [AttributeType] taxonomy, the
 * [TrustAnchor] value class with its [AnchorClass]es, the [Claim]/[ClaimRequirement] shapes,
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

    given("TrustAnchor") {
        then("the two named constants carry their wire values") {
            TrustAnchor.EXT_STAMMDATEN.value shouldBe "ext_stammdaten"
            TrustAnchor.SELF_REPORTED.value shouldBe "self-reported"
        }
        then("of() names the proving tool by its toolId") {
            TrustAnchor.of(ToolId("ident-eid")).value shouldBe "ident-eid"
        }
    }

    given("AnchorClass") {
        then("rank encodes the precedence order: Stammdaten > Proven > Self-reported") {
            (AnchorClass.STAMMDATEN.rank > AnchorClass.PROVEN.rank) shouldBe true
            (AnchorClass.PROVEN.rank > AnchorClass.SELF_REPORTED.rank) shouldBe true
        }
        then("anchorClassOf maps every anchor kind to its class") {
            anchorClassOf(TrustAnchor.EXT_STAMMDATEN) shouldBe AnchorClass.STAMMDATEN
            anchorClassOf(TrustAnchor.SELF_REPORTED) shouldBe AnchorClass.SELF_REPORTED
            anchorClassOf(TrustAnchor.of(ToolId("ident-eid"))) shouldBe AnchorClass.PROVEN
        }
    }

    given("Claim") {
        val claim = Claim(
            attributeType = AttributeType.KVNR,
            value = "A123456789",
            trustAnchor = TrustAnchor.EXT_STAMMDATEN,
            establishedLoa = AcrLevel.LOA2
        )
        then("carries value, provenance and assurance") {
            claim.attributeType shouldBe AttributeType.KVNR
            claim.value shouldBe "A123456789"
            claim.trustAnchor shouldBe TrustAnchor.EXT_STAMMDATEN
            claim.establishedLoa shouldBe AcrLevel.LOA2
        }
        then("establishedLoa defaults to null") {
            Claim(AttributeType.EMAIL, "a@b.de", TrustAnchor.of(ToolId("enroll-email"))).establishedLoa shouldBe null
        }
    }

    given("ClaimRequirement") {
        then("mirrors a claim's attribute type with a minimum anchor class") {
            val requirement = ClaimRequirement(AttributeType.EMAIL, AnchorClass.PROVEN)
            requirement.attributeType shouldBe AttributeType.EMAIL
            requirement.minAnchorClass shouldBe AnchorClass.PROVEN
        }
    }

    given("ClaimDeclaration") {
        then("declares an attribute type with the anchor a run asserts it with") {
            val declaration = ClaimDeclaration(AttributeType.KVNR, TrustAnchor.EXT_STAMMDATEN)
            declaration.attributeType shouldBe AttributeType.KVNR
            declaration.trustAnchor shouldBe TrustAnchor.EXT_STAMMDATEN
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
                ClaimDeclaration(AttributeType.KVNR, TrustAnchor.EXT_STAMMDATEN),
                ClaimDeclaration(AttributeType.EMAIL, TrustAnchor.of(toolId))
            )
        }
        then("accepts reported claims that match the declaration") {
            assertClaimsCovered(
                descriptor,
                listOf(
                    Claim(AttributeType.KVNR, "A123456789", TrustAnchor.EXT_STAMMDATEN, AcrLevel.LOA2),
                    Claim(AttributeType.EMAIL, "a@b.de", TrustAnchor.of(descriptor.toolId))
                )
            )
        }
        then("accepts an empty report") {
            assertClaimsCovered(descriptor, emptyList())
        }
        then("rejects an undeclared attribute type") {
            shouldThrow<IllegalStateException> {
                assertClaimsCovered(descriptor, listOf(Claim(AttributeType.NAME, "Muster", TrustAnchor.EXT_STAMMDATEN)))
            }.message shouldContain "declares none"
        }
        then("rejects a trust anchor that differs from the declaration") {
            shouldThrow<IllegalStateException> {
                assertClaimsCovered(descriptor, listOf(Claim(AttributeType.KVNR, "A123456789", TrustAnchor.of(descriptor.toolId))))
            }.message shouldContain "but declares"
        }
    }
})
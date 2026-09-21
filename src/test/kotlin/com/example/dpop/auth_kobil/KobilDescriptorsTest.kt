package com.example.dpop.auth_kobil

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins what the two KOBIL tools declare about themselves. Pure unit test: descriptors are plain
 * Kotlin objects, no Spring context needed.
 */
class KobilDescriptorsTest : BehaviorSpec({

    given("the KOBIL descriptors") {
        then("both price the method identically - a sibling-by-method-name lookup must not depend on bean order") {
            EnrollKobilDescriptor.method shouldBe AuthKobilDescriptor.method
            EnrollKobilDescriptor.factorTypes shouldBe AuthKobilDescriptor.factorTypes
            EnrollKobilDescriptor.maxAcr shouldBe AuthKobilDescriptor.maxAcr
        }

        then("they declare loa2 from all three factor types, exactly as the device method does") {
            AuthKobilDescriptor.maxAcr shouldBe AcrLevel.LOA2
            AuthKobilDescriptor.factorTypes shouldBe
                setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
        }

        then("their roles differ - that pair is what tells one tool of a method from the other") {
            EnrollKobilDescriptor.role shouldBe MethodRole.ENROLLMENT
            AuthKobilDescriptor.role shouldBe MethodRole.IDENTIFIED_AUTH
        }

        then("neither starts on its role's default step") {
            EnrollKobilDescriptor.startStep shouldBe "activate"
            // The client unlocks locally first; the proof is a step later.
            AuthKobilDescriptor.startStep shouldBe "unlock"
        }

        then("both allow several instances - one credential per phone") {
            EnrollKobilDescriptor.allowsMultipleInstances shouldBe true
            AuthKobilDescriptor.allowsMultipleInstances shouldBe true
        }
    }

    given("the caller-key binding rule") {
        val binding = AuthKobilDescriptor.keyBinding!!

        then("an instance matches only the installation whose DPoP key it was enrolled on") {
            binding.livesOn(mapOf(KOBIL_BINDING_KEY_REF to "key-a"), "key-a") shouldBe true
            binding.livesOn(mapOf(KOBIL_BINDING_KEY_REF to "key-a"), "key-b") shouldBe false
        }

        then("a caller without a key never matches a real instance") {
            binding.livesOn(mapOf(KOBIL_BINDING_KEY_REF to "key-a"), null) shouldBe false
        }

        then("the rule reads the binding key, never the device identifier - those answer different questions") {
            // The KOBIL identifier is only known after an assertion has been redeemed, so it
            // cannot decide whether to OFFER this credential.
            binding.livesOn(mapOf(KOBIL_DEVICE_ID to "dev-1"), "dev-1") shouldBe false
        }
    }
})

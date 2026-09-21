package com.example.dpop.auth_kobil.internal

import com.example.dpop.auth_kobil.api.v1.KobilUnlockCredential
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.tool_spi.FactorType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * The two places where "which unlock was it" turns into "what may this run claim". Both are
 * derivations from a closed type rather than tables someone has to keep aligned, and this test is
 * what says so out loud.
 */
class KobilFactorsTest : BehaviorSpec({

    given("an unlock credential") {
        then("the biometric secret maps to the biometric access means") {
            KobilUnlockCredential.BiometricUnlock("secret").userVerification shouldBe UserVerification.BIOMETRIC
        }

        then("the account password maps to pin - knowledge, and deliberately not a password amr entry") {
            // An amr entry named "password" would make the journey recorder attach the account's
            // real password enrollment to a KOBIL run, counting that credential twice.
            val unlock: KobilUnlockCredential = KobilUnlockCredential.PasswordUnlock("hunter2")
            unlock.userVerification shouldBe UserVerification.PIN
            unlock.userVerification.wireValue shouldBe "pin"
        }
    }

    given("an access means") {
        then("possession is always part of what a run proves - it is the redeemed assertion") {
            UserVerification.entries.forEach { it.kobilFactorTypes() shouldContain FactorType.POSSESSION }
        }

        then("the second factor follows the access means, and only those two shapes exist") {
            UserVerification.PIN.kobilFactorTypes() shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            UserVerification.BIOMETRIC.kobilFactorTypes() shouldBe setOf(FactorType.POSSESSION, FactorType.INHERENCE)
        }
    }
})

package com.example.dpop.auth_kobil.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch

class KobilSecretsTest : BehaviorSpec({

    val secrets = KobilSecrets(pinLength = 8)

    given("a freshly minted PIN") {
        then("it has the configured length and is all digits - the SDK takes a numeric PIN") {
            val pin = secrets.newPin()
            pin shouldHaveLength 8
            pin shouldMatch Regex("\\d{8}")
        }

        then("two PINs differ") {
            secrets.newPin() shouldNotBe secrets.newPin()
        }
    }

    given("an unlock secret") {
        then("it is long enough that a plain digest is the right store for it") {
            // 32 random bytes, base64url without padding: no small preimage space to defend, which
            // is what a password KDF would be for.
            secrets.newUnlockSecret() shouldHaveLength 43
        }

        then("its hash matches only itself") {
            val secret = secrets.newUnlockSecret()
            val hash = secrets.hash(secret)
            secrets.matches(secret, hash) shouldBe true
            secrets.matches(secrets.newUnlockSecret(), hash) shouldBe false
        }

        then("a garbage candidate is rejected rather than blowing up") {
            secrets.matches("not-a-secret", secrets.hash(secrets.newUnlockSecret())) shouldBe false
        }
    }
})

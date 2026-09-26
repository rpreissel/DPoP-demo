package com.example.dpop.auth_password.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Review 2026-09, Phase F: Argon2id for every new hash; old PBKDF2 hashes still verify and move over on the next successful login. */
class PasswordHasherTest : BehaviorSpec({

    /** A hash as the PBKDF2 hasher wrote it before Argon2id: `iterations:salt:hash`. */
    fun legacyHash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 210_000, 256)).encoded
        return "210000:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(key)}"
    }

    given("a new password") {
        then("it is hashed with Argon2id and verifies") {
            val hash = PasswordHasher.hash("correct-horse-battery")
            hash shouldStartWith "\$argon2id\$"
            PasswordHasher.matches("correct-horse-battery", hash) shouldBe true
            PasswordHasher.matches("wrong-horse-battery", hash) shouldBe false
            PasswordHasher.needsRehash(hash) shouldBe false
        }
    }

    given("a password hashed with PBKDF2 before the switch") {
        then("it still verifies, is due for a rehash, and the rehash moves it to Argon2id") {
            val enrollment = AuthPasswordEnrollment(passwordHash = legacyHash("correct-horse-battery"))
            PasswordHasher.matches("correct-horse-battery", enrollment.passwordHash) shouldBe true
            PasswordHasher.needsRehash(enrollment.passwordHash) shouldBe true

            PasswordHasher.upgrade(enrollment, "correct-horse-battery")

            enrollment.passwordHash!! shouldStartWith "\$argon2id\$"
            PasswordHasher.matches("correct-horse-battery", enrollment.passwordHash) shouldBe true
        }

        then("a wrong password neither verifies nor triggers anything") {
            PasswordHasher.matches("wrong-horse-battery", legacyHash("correct-horse-battery")) shouldBe false
        }
    }

    given("no stored hash at all") {
        then("nothing matches (and the full work is still spent - see PasswordHasher.matches)") {
            PasswordHasher.matches("anything", null) shouldBe false
            PasswordHasher.matches("anything", "garbage") shouldBe false
        }
    }
})

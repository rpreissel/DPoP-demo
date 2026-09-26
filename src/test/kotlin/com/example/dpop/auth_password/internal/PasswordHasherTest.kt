package com.example.dpop.auth_password.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/** Review 2026-09, Phase F: Argon2id for every hash; weaker Argon2id parameters move over on the next successful login. */
class PasswordHasherTest : BehaviorSpec({

    given("a new password") {
        then("it is hashed with Argon2id and verifies") {
            val hash = PasswordHasher.hash("correct-horse-battery")
            hash shouldStartWith "\$argon2id\$"
            PasswordHasher.matches("correct-horse-battery", hash) shouldBe true
            PasswordHasher.matches("wrong-horse-battery", hash) shouldBe false
            PasswordHasher.needsRehash(hash) shouldBe false
        }
    }

    given("an Argon2id hash with weaker parameters than today's") {
        then("it verifies, is due for a rehash, and the rehash moves it to today's parameters") {
            val weak = org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 4_096, 1).encode("correct-horse-battery")
            val enrollment = AuthPasswordEnrollment(passwordHash = weak)
            PasswordHasher.matches("correct-horse-battery", enrollment.passwordHash) shouldBe true
            PasswordHasher.needsRehash(enrollment.passwordHash) shouldBe true

            PasswordHasher.upgrade(enrollment, "correct-horse-battery")

            PasswordHasher.needsRehash(enrollment.passwordHash) shouldBe false
            PasswordHasher.matches("correct-horse-battery", enrollment.passwordHash) shouldBe true
        }
    }

    given("a hash in any other format") {
        then("it never matches - there is no older format to accept") {
            PasswordHasher.matches("x", "210000:c2FsdA==:aGFzaA==") shouldBe false
        }
    }

    given("no stored hash at all") {
        then("nothing matches (and the full work is still spent - see PasswordHasher.matches)") {
            PasswordHasher.matches("anything", null) shouldBe false
            PasswordHasher.matches("anything", "garbage") shouldBe false
        }
    }
})

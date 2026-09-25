package com.example.dpop.orchestrator.kc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pure unit test of [resolveMirror] - which Keycloak user the account sync writes (review
 * 2026-09, S-2). The attack it guards: a Keycloak user that set the victim's address on itself
 * must never become the victim's mirror while it still belongs to its own live account.
 */
class KeycloakMirrorResolutionTest : BehaviorSpec({

    val live = setOf(1L, 2L)
    val exists: (Long) -> Boolean = { it in live }

    given("the account's own mirror carries the attribute") {
        then("it is written, even if nobody else wears the address") {
            resolveMirror(1, listOf(KcUser("u1", 1)), null, exists) shouldBe "u1"
        }
        then("it is written when it also wears the address") {
            resolveMirror(1, listOf(KcUser("u1", 1)), KcUser("u1", 1), exists) shouldBe "u1"
        }
        then("a LIVE other account's user wearing the address is a conflict") {
            shouldThrow<IllegalStateException> {
                resolveMirror(1, listOf(KcUser("u1", 1)), KcUser("u2", 2), exists)
            }
        }
    }

    given("no user carries the attribute yet") {
        then("nobody wearing the address means create") {
            resolveMirror(1, emptyList(), null, exists) shouldBe null
        }
        then("an unattributed user wearing the address is adopted") {
            resolveMirror(1, emptyList(), KcUser("u9", null), exists) shouldBe "u9"
        }
        then("a leftover of a deleted account wearing the address is adopted") {
            resolveMirror(1, emptyList(), KcUser("u9", 42), exists) shouldBe "u9"
        }
        then("a live other account's user wearing the address is never taken over") {
            shouldThrow<IllegalStateException> {
                resolveMirror(1, emptyList(), KcUser("u2", 2), exists)
            }
        }
    }

    given("two users carry the same attribute") {
        then("the sync refuses to pick one") {
            shouldThrow<IllegalStateException> {
                resolveMirror(1, listOf(KcUser("u1", 1), KcUser("u3", 1)), null, exists)
            }
        }
    }
})

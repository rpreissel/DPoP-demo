package com.example.dpop.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Review 2026-09-26, F-3: outside demo mode, no demo default may survive the start. */
class ProductionModeCheckTest : BehaviorSpec({

    val secret = "x".repeat(32)

    fun check(
        demoMode: Boolean = false,
        disclosure: Boolean = false,
        adminPassword: String = "{bcrypt}\$2a\$10\$abcdefghijklmnopqrstuv",
        h2Console: Boolean = false,
        otpPepper: String = secret,
        lookupSecret: String = secret,
        trustSelfSigned: Boolean = false,
        keycloakBaseUrl: String = "https://keycloak.example",
        orchestratorBaseUrlForKeycloak: String = "https://orchestrator.example",
    ) = ProductionModeCheck(demoMode, disclosure, adminPassword, h2Console, otpPepper, lookupSecret, trustSelfSigned, keycloakBaseUrl, orchestratorBaseUrlForKeycloak)

    given("demo mode") {
        then("the demo defaults are allowed - nothing is checked") {
            check(demoMode = true, disclosure = true, adminPassword = "admin", h2Console = true, otpPepper = "", lookupSecret = "", trustSelfSigned = true, keycloakBaseUrl = "http://keycloak", orchestratorBaseUrlForKeycloak = "http://orchestrator")
        }
    }

    given("demo mode off with a configuration fit for real people") {
        then("it starts") {
            check().violations().shouldBeEmpty()
        }
    }

    given("demo mode off with every demo default still in place") {
        then("it refuses to start and names each of them at once") {
            val failure = shouldThrow<IllegalStateException> {
                check(disclosure = true, adminPassword = "admin", h2Console = true, otpPepper = "", lookupSecret = "short", trustSelfSigned = true, keycloakBaseUrl = "http://keycloak:8080", orchestratorBaseUrlForKeycloak = "http://orchestrator:8080")
            }
            listOf("demo.disclosure", "demo.admin.password", "spring.h2.console", "otp-pepper", "lookup-secret", "trustSelfSignedCertificate", "http://keycloak", "http://orchestrator").forEach {
                failure.message!! shouldContain it
            }
        }
    }

    given("an admin password in plain text") {
        then("it is refused even when it is not the demo value - the login needs a hash") {
            // Built in demo mode so the constructor does not refuse it outright - violations() is the same list.
            check(demoMode = true, adminPassword = "correct-horse-battery-staple").violations().single() shouldContain "Klartext"
        }
    }

    given("no Keycloak configured (the non-keycloak profile)") {
        then("the https rule does not apply") {
            check(keycloakBaseUrl = "", orchestratorBaseUrlForKeycloak = "").violations().size shouldBe 0
        }
    }
})

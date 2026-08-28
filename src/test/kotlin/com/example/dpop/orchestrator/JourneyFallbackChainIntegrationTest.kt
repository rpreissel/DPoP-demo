package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * The properties that only exist because the model is a per-intent state machine
 * (docs/04-orchestrierung.md): the FAST fallback chain and its memory of what was declined,
 * identification as a LOGIN path when nothing else is left, the states LOGIN_LOOKUP structurally
 * does not have, and the journey-wide attempt budget.
 *
 * Deliberately separate from RegistrationLoginStepUpFlowIntegrationTest, which covers the happy
 * paths of each intent end to end.
 */
class JourneyFallbackChainIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    /** Registers the seeded test person with sms only and returns the resulting accountId. */
    private fun registerWithSms(): Long {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM account", Long::class.java)!!
    }

    init {
        given("the per-intent state machine's fallback chain, attempt budget, and entry intent") {
        // Fallback chain -----------------------------------------------------------

        then("Fast chain declining the auth state falls through to identification") {
            registerWithSms()

            // Fresh session on the same device: the link routes straight to the existing sms method.
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val next = get("/orchestrator/api/v1/app/channels/$channelSessionId").next()
            next shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")

            // Backing out of the only remaining auth method is not a dead end: the chain falls
            // through to its last state, identification - which is exactly what the old model could
            // not express, because an empty candidate list aborted the whole process.
            val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
            val afterDecline = delete("/orchestrator/api/v1/tools/$toolSessionId/auth-sms")
            afterDecline.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
            @Suppress("UNCHECKED_CAST")
            (afterDecline.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("ident-fsc", "ident-eid")
        }

        then("Fast chain identifying after declining auth logs into the same account without registering again") {
            val accountId = registerWithSms()

            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val authToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
            delete("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms")

            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            val identified = patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )

            // The same KVNR finds the SAME account again - "registration" versus "login" was never a
            // choice made up front, only an observation about which path was taken. So identifying on
            // the last state must not leave a second account behind.
            identified.next()["type"].shouldNotBeNull()
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Int::class.java) shouldBe 1
            jdbcTemplate.queryForObject("SELECT MIN(id) FROM account", Long::class.java) shouldBe accountId
        }

        // LOGIN_LOOKUP -------------------------------------------------------------

        then("Lookup login cannot be talked into an identification") {
            registerWithSms()

            val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                .channel()["channelSessionId"] as String

            // No state of this intent ever offers an identification, so naming the tool directly is
            // rejected at the boundary rather than blowing up deeper in with a 500.
            val exception = assertThrows<HttpClientErrorException> {
                post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc")
            }
            exception.statusCode shouldBe HttpStatus.CONFLICT
        }

        // Attempt budget -----------------------------------------------------------

        then("Attempt budget spans the whole journey not a single tool") {
            registerWithSms()
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

            // Two failures on one tool session, the third on a FRESH one: under a per-tool counter
            // the fresh session would start over at zero and the journey would survive indefinitely.
            val firstToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"000000"}""")
            patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"000000"}""")

            val secondToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"000000"}""")
            }
            exception.statusCode shouldBe HttpStatus.GONE
        }

        // Entry intent -------------------------------------------------------------

        then("Cancelling a lookup login restarts a lookup login not a registration") {
            registerWithSms()
            currentBindingKeyRef = "binding-" + UUID.randomUUID()

            val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                .channel()["channelSessionId"] as String
            val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String

            // The channel remembers WHICH intent it was entered with, so abandoning does not silently
            // turn a "log me into my existing account" into "let's register you".
            delete("/orchestrator/api/v1/tools/$toolSessionId/auth-sms-lookup")
            val afterCancel = delete("/orchestrator/api/v1/app/channels/$channelSessionId/journey")
            afterCancel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
            @Suppress("UNCHECKED_CAST")
            afterCancel.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")
        }
        }
    }
}

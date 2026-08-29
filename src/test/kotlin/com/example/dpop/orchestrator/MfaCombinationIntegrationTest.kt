package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Combining two factor types to reach a higher ACR than either alone
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class MfaCombinationIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("requesting a step-up to a level no active method reaches") {
                then("re-identification is offered first; declining it falls back to the already-authenticated channel") {

                // Reuse the happy path up to AUTHENTICATED at loa2 with a single, already-used sms factor.
                val channelSessionId = registerAndAuthenticate()

                // loa3 requires two distinct factor types; this account only ever proves POSSESSION with
                // its active methods - but ident-eid (unused this session, maxAcr=loa3) COULD still reach
                // it, so the account is offered that way out instead of dead-ending immediately.
                val offered = post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")
                offered.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Declining just gives up on the step-up (Decision.Cancel) - the channel's entry
                // intent (FAST_ACCESS) restarts fresh from there. Its only active method (sms) is
                // already used this session, so - same generic fallback chain any FAST_ACCESS run
                // takes - it falls through to offering identification again, same as a first-time
                // visitor; unrelated to the new re-identification feature itself.
                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")


                }
            }
        }

        given("a fresh channel") {
            `when`("combining sms and password, each only loa1 alone") {
                then("together they reach loa2") {

                // Channel requires loa2 up front, so registration can't stop after a single loa1-rated
                // factor - it must chain further, differently-typed ones too.
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )

                // First factor (sms): alone it's loa1, not the required loa2, so registration continues.
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                // Two candidates are left (enroll-email, enroll-device; sms is already active, password
                // still needs a confirmed email first) - a selection page is offered, not a single-
                // candidate skip.
                afterSms.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                afterSms.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("enroll-email", "enroll-device")

                // Second factor (email): sms+email are BOTH possession, so this alone still doesn't
                // reach loa2 - but it unlocks enroll-password (requiresConfirmedEmail).
                enrollEmail(channelSessionId)

                // Third factor (password, a KNOWLEDGE factor): together with sms/email this combines to loa2.
                val enrollPasswordToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                val enrolled = patch(
                    "/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password",
                    """{"password":"correct-horse-battery"}"""
                )
                enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
                finalChannel.channel()["currentAcr"] shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                finalChannel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("fsc", "sms", "email", "password")

                // --- Fresh app session on the same device (no re-identification, so fsc's own loa2 isn't in play this time) ---
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                // No single method alone reaches loa2, so the login offers a pick among all three.
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email", "auth-password")

                val (loginTan, smsActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authSmsToolSessionId = smsActivation.nextRaw()["toolSessionId"] as String
                val afterSmsAuth = patch("/orchestrator/api/v1/tools/$authSmsToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
                // sms alone is only loa1, and email would be the SAME factor type (no MFA progress) - only
                // password is offered next.
                afterSmsAuth.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")

                // Same rule as above: activate auth-password explicitly, don't reuse afterSmsAuth's (auth-sms) session id.
                val authPasswordToolSessionId = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
                val authenticated = patch(
                    "/orchestrator/api/v1/tools/$authPasswordToolSessionId/auth-password",
                    """{"password":"correct-horse-battery"}"""
                )
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["currentAcr"] shouldBe "loa2"


                }
            }
        }
    }
}

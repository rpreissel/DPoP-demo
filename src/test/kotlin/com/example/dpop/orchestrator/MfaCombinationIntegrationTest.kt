package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
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

                // Reuse the happy path up to AUTHENTICATED at loa2 with SMS and email evidence.
                val channelSessionId = loginAsSeededAccount()

                // SMS and email combine to loa2, but neither method can reach loa3. ident-eid
                // (unused this session, maxAcr=loa3) can, so it is offered instead of dead-ending.
                val offered = post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")
                offered.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Declining RE_IDENTIFY (Transition.Cancel) only gives up on THAT sub-journey - its
                // parent (this STEP_UP) was merely SUSPENDED waiting for it, not gone, so it
                // resumes (JourneyService: SUSPENDED-parent handoff, symmetric to a successful
                // finish()) via JourneyEvent.SubJourneyCancelled. StepUpState.Start reacts to that
                // event itself and gives up (Transition.Cancel) instead of blindly re-deriving from
                // unchanged evidence, which would just re-offer the identical confirm prompt
                // forever - so the channel correctly falls back to AUTHENTICATED
                // (StepUpStrategy.onCancel) rather than either looping or discarding the still-valid
                // session underneath this step-up into a fresh FAST_ACCESS identification flow.
                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                declined.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("a fresh channel") {
            `when`("combining sms and email, each only loa1 alone") {
                then("together they reach loa2") {

                // Channel requires loa2 up front, so registration can't stop after a single loa1-rated
                // factor - it must chain further, differently-typed ones too.
                val channelSessionId = identifyAndConfirmEmail(requiredAcr = "loa2")
                // First factor (sms): alone it's loa1, not the required loa2, so registration continues.
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                // The address was already confirmed before any enrollment (ADR-17). sms alone is
                // loa1, so more methods are offered - enroll-password among them, which is exactly
                // the knowledge factor this channel's loa2 floor needs.
                @Suppress("UNCHECKED_CAST")
                (afterSms.stepData()["options"] as List<String>) shouldContain "enroll-password"
                // Second factor: the password (KNOWLEDGE) complements sms (POSSESSION) - two
                // different factor types, so together they reach loa2 and authentication succeeds.
                enrollPassword(channelSessionId)

                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
                finalChannel.channel()["currentAcr"] shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                finalChannel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("fsc", "sms", "password")

                // --- Fresh app session on the same device (no re-identification, so fsc's own loa2 isn't in play this time) ---
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                // Fresh login: sms and email are each loa1 alone, but their POSSESSION and KNOWLEDGE
                // factors combine to reach loa2.
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")

                val afterSmsAuth = authenticateViaSms(newChannelSessionId)
                // sms alone is only loa1; email is KNOWLEDGE (different type), so it's offered next
                // for MFA to reach loa2.
                afterSmsAuth.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")

                // The account's KNOWLEDGE factor is the password since confirming an address stopped
                // leaving a method behind.
                val emailActivation = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-password")
                val authEmailToolSessionId = emailActivation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["currentAcr"] shouldBe "loa2"


                }
            }
        }
    }
}

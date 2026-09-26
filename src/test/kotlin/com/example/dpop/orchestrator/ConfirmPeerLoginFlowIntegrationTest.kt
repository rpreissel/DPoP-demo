package com.example.dpop.orchestrator

import com.example.dpop.texts.templateOf
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import com.example.dpop.orchestrator.domain.AuthIntent

/**
 * `AuthIntent.CONFIRM_PEER_LOGIN` (docs/04-orchestrierung.md): the loa2 gate and its
 * "no account at all -> abort, never identification" guard. Stops short of an actual approval -
 * `confirm-qr-login` itself lives in a separate module (`auth_qr`, docs/03-tool-architektur.md),
 * exercised end-to-end by `AuthQrFlowIntegrationTest` - this only proves the intent/journey
 * mechanics up to the point that tool would activate.
 */
class ConfirmPeerLoginFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a cold device with no DeviceAccountLink") {
            `when`("entering with intent=confirm_peer_login") {
                then("the journey aborts immediately - never identification/registration") {

                val exception = assertThrows<HttpClientErrorException> {
                    post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                }
                exception.statusCode shouldBe HttpStatus.GONE

                }
            }
        }

        given("a device already linked to an account with only sms enrolled (loa1)") {
            `when`("entering with intent=confirm_peer_login") {
                then("it gates on loa2 via the normal STEP_UP sub-journey, offering the account's own methods") {

                registerWithSmsOnly()

                val response = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                // Not STEP_UP_IN_PROGRESS: this channel was never logged in, and that state promises a
                // cancel leads back to AUTHENTICATED (ChannelState.isLoggedIn).
                response.channel()["state"] shouldBe "ANONYMOUS"
                response.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")

                }
            }

            `when`("the user cancels while the step-up sub-journey runs") {
                then("the channel falls back to not logged in - never AUTHENTICATED without a proof - and nothing is left waiting") {

                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                    .channel()["channelSessionId"] as String

                // Used to claim AUTHENTICATED (STEP_UP's own fallback) - since V22 the database
                // refused that with a 500 (docs/invarianten.md I-4).
                delete("/orchestrator/api/v1/channels/$channelSessionId/journey").channel()["state"] shouldNotBe "AUTHENTICATED"
                // Both the confirmation and its step-up are cancelled - the parent is not left
                // SUSPENDED. (The entry intent then starts afresh, with a new pair.)
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ? AND lifecycle = 'CANCELLED'",
                    Int::class.java, java.util.UUID.fromString(channelSessionId)
                ) shouldBe 2

                }
            }
        }

        given("a device linked to an account whose only active method (sms) is now used up, still under loa2") {
            `when`("the STEP_UP gate re-evaluates with no active method left to offer") {
                then("aborts (410) instead of falling back to RE_IDENTIFY - a peer-approval must never trigger identification") {

                registerWithSmsOnly()

                val started = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                val channelSessionId = started.channel()["channelSessionId"] as String
                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
                }
                exception.statusCode shouldBe HttpStatus.GONE
                templateOf(exception.getResponseBodyAs(Map::class.java)!!["text"]) shouldContain "nicht erreichbar"

                }
            }
        }
    }
}

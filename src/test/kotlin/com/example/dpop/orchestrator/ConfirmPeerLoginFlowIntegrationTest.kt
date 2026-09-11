package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

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

    private fun registerWithSms(): Long {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM account", Long::class.java)!!
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

                registerWithSms()

                val response = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                response.channel()["state"] shouldBe "STEP_UP_IN_PROGRESS"
                response.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")

                }
            }
        }
    }
}

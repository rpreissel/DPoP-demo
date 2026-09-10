package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Cross-channel QR login (docs/ideen/qr-login-ueber-app.md): a WEB `auth-qr-lookup`/`auth-qr`
 * activation is resolved by an already-authenticated APP channel's `confirm-qr-login`. Both sides
 * are the same orchestrator process/DB - no real Keycloak needed, only peer-auth is mocked
 * (same pattern as [KcChannelIntegrationTest]).
 */
class AuthQrFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(channelAnchor: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelAnchor = channelAnchor,
            subject = null
        )
    }

    private fun kcHeaders(): HttpHeaders = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun kcPatch(channelSessionId: UUID, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId",
            HttpMethod.PATCH,
            HttpEntity(withDefaultAvailableTools(body), kcHeaders()),
            mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    private fun kcPost(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    private fun kcPatchTool(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Registers+authenticates on the APP channel, then enrolls the qr opt-in on the same, still-AUTHENTICATED channel. */
    private fun registerWithQrOptIn(): Pair<String, Long> {
        val channelSessionId = registerAndAuthenticate()
        val accountId = jdbcTemplate.queryForObject(
            "SELECT id FROM account ORDER BY id DESC LIMIT 1", Long::class.java
        )!!

        post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-qr").nextRaw()["toolSessionId"] as String
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-qr", "{}")
        enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

        return channelSessionId to accountId
    }

    /** Activates auth-qr-lookup on a fresh WEB channel, returns (toolSessionId, pairingCode, verificationCode). */
    private fun startWebLookup(): Triple<String, String, String> {
        val webChannelSessionId = UUID.randomUUID()
        stubAssertion(channelAnchor = "kc-auth-session-${UUID.randomUUID()}")
        kcPatch(webChannelSessionId)
        val webToolSessionId = kcPost("/orchestrator/api/v1/channels/$webChannelSessionId/tools/auth-qr-lookup")
            .nextRaw()["toolSessionId"] as String
        val waiting = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
        val pairingCode = waiting.stepData()["pairingCode"] as String
        val verificationCode = waiting.stepData()["verificationCode"] as String
        return Triple(webToolSessionId, pairingCode, verificationCode)
    }

    init {
        given("an account with the qr opt-in, and a WEB channel waiting on auth-qr-lookup") {
            `when`("that same account confirms via confirm-qr-login on its own authenticated channel") {
                then("the WEB channel's next poll sees Completed.Authenticated with the right account") {

                val (webToolSessionId, pairingCode, verificationCode) = startWebLookup()
                val (appChannelSessionId, accountId) = registerWithQrOptIn()

                val started = post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "input")
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                val confirmStep = patch(
                    "/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login",
                    """{"pairingCode":"$pairingCode"}"""
                )
                confirmStep.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "confirm")
                (confirmStep.stepData()["verificationCode"] as String) shouldBe verificationCode

                val approved = patch(
                    "/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login",
                    """{"decision":"accept"}"""
                )
                approved.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val resolved = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                resolved.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                (resolved["authData"] as Map<*, *>)["accountId"] shouldBe accountId

                }
            }
        }

        given("a pending pairing that gets rejected instead of accepted") {
            `when`("the APP side declines") {
                then("the WEB channel's next poll fails, the journey does not silently continue") {

                val (webToolSessionId, pairingCode, _) = startWebLookup()
                val (appChannelSessionId, _) = registerWithQrOptIn()

                post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"pairingCode":"$pairingCode"}""")
                val declined = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"reject"}""")
                declined.stepData()["error"].shouldNotBeNull()

                // DENIED, not silently APPROVED - the WEB side's next poll reports the same
                // rejection rather than ever completing.
                val stillFailing = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                stillFailing.stepData()["error"].shouldNotBeNull()

                }
            }
        }

        given("an account without the qr opt-in") {
            `when`("its own channel tries to confirm a pending pairing") {
                then("it is rejected, never silently approved") {

                val (webToolSessionId, pairingCode, _) = startWebLookup()

                // A DIFFERENT account that never enrolled qr.
                val noOptInChannelSessionId = registerAndAuthenticate()
                post("/orchestrator/api/v1/channels/$noOptInChannelSessionId/peer-logins")
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$noOptInChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"pairingCode":"$pairingCode"}""")
                val rejected = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"accept"}""")
                rejected.stepData()["error"].shouldNotBeNull()

                // Never resolved (resolveIfPending is never reached without the opt-in) - the WEB
                // side is still waiting, not silently authenticated and not aborted either.
                val stillWaiting = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                stillWaiting.next()["step"] shouldBe "waitForApp"

                }
            }
        }
    }
}

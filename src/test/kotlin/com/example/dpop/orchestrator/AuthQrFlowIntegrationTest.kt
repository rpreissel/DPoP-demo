package com.example.dpop.orchestrator

import org.springframework.web.client.HttpClientErrorException
import org.junit.jupiter.api.assertThrows
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Cross-channel QR login (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN): a WEB `auth-qr-lookup`/`auth-qr`
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
        val channelSessionId = loginAsSeededAccount()
        val accountId = jdbcTemplate.queryForObject(
            "SELECT id FROM account.account ORDER BY id DESC LIMIT 1", Long::class.java
        )!!

        post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-qr").nextRaw()["toolSessionId"] as String
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-qr", "{}")
        enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

        return channelSessionId to accountId
    }

    /**
     * `registerWithQrOptIn`'s channel independently satisfies loa2 by the time `/peer-logins` runs
     * (sms + email both active) - `ConfirmPeerLoginStrategy`'s re-proof requirement (docs/04-
     * orchestrierung.md, CONFIRM_PEER_LOGIN, same shape as `DeleteAccountStrategy`) then demands one fresh
     * factor before `confirm-qr-login` is offered, regardless of that existing evidence. Resolves
     * it via auth-sms, same TAN pattern as every other auth-sms test in this suite.
     */
    private fun resolveReconfirmation(appChannelSessionId: String) {
        val resolved = authenticateViaSms(appChannelSessionId)
        resolved.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "input")
    }

    /** Activates auth-qr-lookup on a fresh WEB channel, returns (toolSessionId, pairingCode). */
    private fun startWebLookup(): Pair<String, String> {
        val webChannelSessionId = UUID.randomUUID()
        stubAssertion(channelAnchor = webChannelSessionId.toString())
        kcPatch(webChannelSessionId)
        val webToolSessionId = kcPost("/orchestrator/api/v1/channels/$webChannelSessionId/tools/auth-qr-lookup")
            .nextRaw()["toolSessionId"] as String
        val waiting = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
        val pairingCode = waiting.stepData()["pairingCode"] as String
        return webToolSessionId to pairingCode
    }

    /**
     * Drives the APP side up to and including the approval; returns (accountId, confirmationCode) -
     * the code the app shows, once, to be typed into the browser.
     */
    private fun approveOnApp(pairingCode: String): Pair<Long, String> {
        val (appChannelSessionId, accountId) = registerWithQrOptIn()
        post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
        resolveReconfirmation(appChannelSessionId)
        val confirmToolSessionId =
            post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"pairingCode":"$pairingCode"}""")
        val shown = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"accept"}""")
        shown.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "showCode")
        return accountId to (shown.stepData()["confirmationCode"] as String)
    }

    init {
        given("an account with the qr opt-in, and a WEB channel waiting on auth-qr-lookup") {
            `when`("that same account confirms via confirm-qr-login on its own authenticated channel") {
                then("the app shows a confirmation code, and only that code typed into the browser logs it in") {

                val (webToolSessionId, pairingCode) = startWebLookup()
                val (appChannelSessionId, accountId) = registerWithQrOptIn()

                val started = post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                resolveReconfirmation(appChannelSessionId)
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                val confirmStep = patch(
                    "/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login",
                    """{"pairingCode":"$pairingCode"}"""
                )
                confirmStep.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "confirm")

                val shown = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"accept"}""")
                shown.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "showCode")
                val confirmationCode = shown.stepData()["confirmationCode"] as String

                // Approving alone logs no browser in (review 2026-09, M-2) - it now asks for the code.
                val asking = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                asking.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-qr-lookup", "step" to "enterCode")

                val resolved = kcPatchTool(
                    "/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup",
                    """{"confirmationCode":"$confirmationCode"}"""
                )
                resolved.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                (resolved["authData"] as Map<*, *>)["accountId"] shouldBe accountId

                val done = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"done"}""")
                done.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                }
            }
        }

        given("an approved pairing whose browser does not know the code - a victim approving from a phishing link") {
            `when`("the browser guesses") {
                then("wrong codes fail, and after three the login is aborted and the request burned") {

                val (webToolSessionId, pairingCode) = startWebLookup()
                val (_, confirmationCode) = approveOnApp(pairingCode)
                val wrong = if (confirmationCode == "000000") "000001" else "000000"

                repeat(2) {
                    val guessed = kcPatchTool(
                        "/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup",
                        """{"confirmationCode":"$wrong"}"""
                    )
                    guessed.channel()["state"] shouldNotBe "AUTHENTICATED"
                }
                // The third wrong code also exhausts the journey's own attempt budget - the browser
                // login is aborted, and the request itself is burned regardless.
                val aborted = assertThrows<HttpClientErrorException> {
                    kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup", """{"confirmationCode":"$wrong"}""")
                }
                aborted.statusCode shouldBe HttpStatus.GONE
                jdbcTemplate.queryForObject(
                    "SELECT status FROM auth_qr.login_request WHERE pairing_code = ?", String::class.java, pairingCode
                ) shouldBe "EXPIRED"

                }
            }
        }

        given("a pending pairing that gets rejected instead of accepted") {
            `when`("the APP side declines") {
                then("the WEB channel's next poll fails, the journey does not silently continue") {

                val (webToolSessionId, pairingCode) = startWebLookup()
                val (appChannelSessionId, _) = registerWithQrOptIn()

                post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
                resolveReconfirmation(appChannelSessionId)
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

                val (webToolSessionId, pairingCode) = startWebLookup()

                // A DIFFERENT account that never enrolled qr.
                val noOptInChannelSessionId = loginAsSeededAccount()
                post("/orchestrator/api/v1/channels/$noOptInChannelSessionId/peer-logins")
                resolveReconfirmation(noOptInChannelSessionId)
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$noOptInChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"pairingCode":"$pairingCode"}""")
                val rejected = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"accept"}""")
                rejected.stepData()["error"].shouldNotBeNull()

                // Never resolved (approveIfPending is never reached without the opt-in) - the WEB
                // side is still waiting, not silently authenticated and not aborted either.
                val stillWaiting = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                stillWaiting.next()["step"] shouldBe "waitForApp"

                }
            }
        }
    }
}

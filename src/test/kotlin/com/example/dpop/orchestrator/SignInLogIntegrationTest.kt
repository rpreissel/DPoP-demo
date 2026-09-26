package com.example.dpop.orchestrator

import com.example.dpop.account.SignInLog
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.HttpClientErrorException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/**
 * The account's sign-in log (ADR-39, addendum): what a sign-in, a failed proof, a lockout and a
 * sign-out leave behind - from the app channel, and from Keycloak, whose own logouts and password
 * form the orchestrator only learns about because Keycloak reports them.
 */
class SignInLogIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @Autowired
    private lateinit var signInLog: SignInLog

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun accountOf(channelSessionId: String): Long =
        jdbcTemplate.queryForObject(
            "SELECT account_id FROM orchestrator.channel_session WHERE id = CAST(? AS UUID)", Long::class.java, channelSessionId
        )!!

    private fun typesOf(accountId: Long) = signInLog.of(accountId).map { it.signInType }

    private fun failPassword() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"wrong-password-123"}""")
    }

    private fun stubAssertion(anchor: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns
            PeerAuthAssertion(jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelAnchor = anchor, subject = null)
    }

    private fun kcPost(path: String, body: String? = null) =
        restTemplate.exchange(
            "http://localhost:$port$path", HttpMethod.POST,
            HttpEntity(body, HttpHeaders().apply {
                set("Authorization", "Bearer mock-peer-auth-token")
                set("Content-Type", "application/json")
            }),
            String::class.java
        )

    init {
        given("a returning user on the app") {
            then("the sign-in is logged with its level and proofs, and the logout with who ended it") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = accountOf(channelSessionId)

                val signedIn = signInLog.of(accountId).single()
                signedIn.signInType shouldBe "SIGNED_IN"
                signedIn.channel shouldBe "APP"
                signedIn.acr shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                (signedIn.details["amr"] as List<String>) shouldContainAll listOf("sms", "password")
                signedIn.details["type"] shouldBe "SIGNED_IN"
                signedIn.details["version"] shouldBe 1

                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")

                val signedOut = signInLog.of(accountId).last()
                signedOut.signInType shouldBe "SIGNED_OUT"
                signedOut.details["endedBy"] shouldBe "HOLDER"
            }
        }

        given("a user signed in at loa1 who raises the level") {
            then("the step-up is its own entry after the sign-in, with the level it reached (review 2026-09-26, T-1)") {
                seedRegisteredAccount()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                authenticateViaSms(channelSessionId)
                post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa2"}""")
                authenticateViaPassword(channelSessionId)
                val accountId = accountOf(channelSessionId)

                typesOf(accountId) shouldBe listOf("SIGNED_IN", "STEPPED_UP")
                val steppedUp = signInLog.of(accountId).last()
                steppedUp.acr shouldBe "loa2"
                steppedUp.channel shouldBe "APP"
                @Suppress("UNCHECKED_CAST")
                (steppedUp.details["amr"] as List<String>) shouldContainAll listOf("sms", "password")
            }
        }

        given("someone guessing an account's password") {
            then("every wrong guess is logged, and the one that locks the account is logged as the lockout") {
                val accountId = seedRegisteredAccount()
                repeat(5) { failPassword() }

                typesOf(accountId) shouldBe List(5) { "SIGN_IN_FAILED" } + "LOCKED_OUT"
                signInLog.of(accountId).first().details["method"] shouldBe "password"
            }
        }

        given("Keycloak's own password form") {
            then("a wrong password there is a failed sign-in on the Keycloak channel") {
                val accountId = seedRegisteredAccount()
                stubAssertion(accountId.toString())

                kcPost("/orchestrator/api/v1/tools/auth-password/mgmt/$accountId", """{"password":"wrong-password-123"}""")

                val failed = signInLog.of(accountId).single()
                failed.signInType shouldBe "SIGN_IN_FAILED"
                failed.channel shouldBe "KEYCLOAK"
            }
        }

        given("a logout that only Keycloak saw") {
            then("Keycloak's report is logged as a sign-out on the Keycloak channel") {
                val accountId = seedRegisteredAccount()
                stubAssertion(accountId.toString())

                kcPost("/orchestrator/api/v1/kc/accounts/$accountId/sign-outs?kcSessionId=kc-session-1").statusCode shouldBe HttpStatus.NO_CONTENT

                val signedOut = signInLog.of(accountId).single()
                signedOut.signInType shouldBe "SIGNED_OUT"
                signedOut.channel shouldBe "KEYCLOAK"
            }

            then("a report anchored to another account is refused and logs nothing") {
                val accountId = seedRegisteredAccount()
                stubAssertion("someone-else")

                assertThrows<HttpClientErrorException> {
                    kcPost("/orchestrator/api/v1/kc/accounts/$accountId/sign-outs?kcSessionId=kc-session-1")
                }

                signInLog.of(accountId) shouldBe emptyList()
            }
        }
    }
}

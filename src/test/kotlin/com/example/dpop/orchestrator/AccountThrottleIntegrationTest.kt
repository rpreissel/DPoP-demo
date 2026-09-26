package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The account-level brute-force lock, spanning fresh channels/tool sessions
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class AccountThrottleIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    /** A wrong password on a fresh auth-password tool - a failed attempt that sends nothing (the send throttle has its own test below). */
    private fun failPassword(channelSessionId: String) {
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"wrong-password-123"}""")
    }

    init {
        given("an account that keeps requesting SMS codes (review 2026-09, Phase F)") {
            `when`("auth-sms is activated a fourth time within the window") {
                then("it is refused with 429 and no code is sent - not a failed attempt") {
                    seedRegisteredAccount()
                    repeat(3) {
                        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                        post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                    }
                    val sentBefore = smsGateway.outbox().size
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    val refused = assertThrows<HttpClientErrorException> {
                        post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                    }
                    refused.statusCode.value() shouldBe 429
                    smsGateway.outbox().size shouldBe sentBefore
                    // The login itself is not locked - a password still works.
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password").nextRaw()["toolSessionId"].shouldNotBeNull()
                }
            }
        }

        given("a fresh channel") {
            `when`("repeatedly failing auth across fresh tool sessions") {
                then("the account-level throttle locks") {

                seedRegisteredAccount()
                // Each iteration is a FRESH channel and therefore a fresh journey with a fresh attempt
                // budget - which is precisely what the journey-local budget cannot catch and the
                // account-level throttle must (docs/04-orchestrierung.md #7).
                repeat(5) {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    failPassword(channelSessionId)
                }

                val lockedChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val exception = assertThrows<HttpClientErrorException> {
                    post("/orchestrator/api/v1/channels/$lockedChannelSessionId/tools/auth-password")
                }
                exception.statusCode.value() shouldBe 423


                }
            }
        }

        given("a fresh channel") {
            `when`("a successful auth follows a few failures") {
                then("the throttle counter resets") {

                seedRegisteredAccount()
                // A few failures, but not enough to lock - then a genuine success should clear the counter.
                // Each failure gets its own channel: the journey-local attempt budget would otherwise end
                // the journey before the account-level counter is anywhere near its own threshold.
                repeat(3) {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    failPassword(channelSessionId)
                }
                val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val authenticated = authenticateViaSms(freshChannelSessionId)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                // Confirm the counter was actually reset, not just "not yet locked": two more fresh
                // failures on ANOTHER new session right after a success should NOT be treated as
                // already at 3/5 - they still land on the same account via the device link.
                repeat(2) {
                    val retryChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    failPassword(retryChannelSessionId)
                }
                // Still allowed - only 2 failures since the reset, well under the lock threshold.
                val nextChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val stillAllowed = post("/orchestrator/api/v1/channels/$nextChannelSessionId/tools/auth-password")
                stillAllowed.nextRaw()["toolSessionId"].shouldNotBeNull()


                }
            }
        }
    }
}

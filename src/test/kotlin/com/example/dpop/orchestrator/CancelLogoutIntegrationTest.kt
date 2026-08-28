package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Cancelling an in-progress process versus logging out of a finished one
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class CancelLogoutIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a fresh channel") {
            `when`("cancelling mid-registration") {
                then("the process resets and offers a fresh start") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                // Get all the way to Identified (account created) before cancelling, to prove the
                // channel doesn't stay half-bound to that account afterwards.
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )

                val cancelled = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
                // ChannelState diagram (docs/02-domaenenmodell.md #3): REGISTERING -> ANONYMOUS -> a
                // fresh registration is offered immediately, so the response already shows REGISTERING
                // again; two ident candidates exist, so a selection page is offered (same as the
                // initial channel init).
                cancelled.channel()["state"] shouldBe "REGISTERING"
                cancelled.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                @Suppress("UNCHECKED_CAST")
                (cancelled.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("ident-fsc", "ident-eid")

                // The old ident-fsc tool session is no longer part of any active process.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT


                }
            }
        }

        given("a fresh channel") {
            `when`("cancelling mid-login") {
                then("a fresh login attempt is offered") {

                registerAndAuthenticate()

                // Simulate a fresh app session on the same device: new channel, straight to LOGIN via the device link.
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")

                val cancelled = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
                // LOGIN cancel doesn't force a channel-state change (docs: only REGISTERING/STEP_UP do);
                // the response re-offers candidates from scratch - two active methods (sms, email) now
                // exist, so that's a selection page, not the single auth-sms tool directly.
                cancelled.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                cancelled.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("logging out of an authenticated channel") {
                then("the channel ends for good and a new one starts a fresh login via the device link") {

                val channelSessionId = registerAndAuthenticate()
                val beforeLogout = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                beforeLogout.channel()["state"] shouldBe "AUTHENTICATED"

                // Unlike Cancel (which leaves an AUTHENTICATED channel untouched, nothing to cancel),
                // Logout always ends the channel.
                deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId") shouldBe HttpStatus.NO_CONTENT

                // The old channelSessionId is dead - GET still resolves it (same key, valid binding),
                // but it stays LOGGED_OUT and reports no next step; it is never silently re-derived.
                val loggedOutChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                loggedOutChannel.channel()["state"] shouldBe "LOGGED_OUT"
                loggedOutChannel["next"].shouldBeNull()
                loggedOutChannel.channel()["currentAcr"].shouldBeNull()

                // A brand-new channel on the SAME device (same DPoP key) still recognizes the account via
                // DeviceAccountLink and skips straight to LOGIN instead of a fresh ident-fsc - two active
                // methods (sms, email) means a selection page, not a direct skip.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                newChannelSessionId shouldNotBe channelSessionId
                newChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                newChannel.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")

                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
                afterLogin.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("a fresh channel") {
            `when`("logging out during active registration") {
                then("the registration process is cancelled too") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )

                deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId") shouldBe HttpStatus.NO_CONTENT

                // The old ident-fsc tool session is no longer part of any active process.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                // Half-registered isn't a returning user (same rule as plain Cancel, docs/06-ablaeufe.md
                // via ProcessCancellationService): no account was ever fully provisioned, so the new
                // channel starts registration again, not LOGIN.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                newChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("logging out with a mismatched binding key") {
                then("it is forbidden") {

                val channelSessionId = registerAndAuthenticate()

                currentBindingKeyRef = "a-completely-different-binding-key"

                val exception = assertThrows<HttpClientErrorException> {
                    deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")
                }
                exception.statusCode shouldBe HttpStatus.FORBIDDEN


                }
            }
        }
    }
}

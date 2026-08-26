package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import org.assertj.core.api.Assertions.assertThat
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
                // again; single-candidate skip goes straight to the tool (same as the initial channel init).
                assertThat(cancelled.channel()["state"]).isEqualTo("REGISTERING")
                assertThat(cancelled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

                // The old ident-fsc tool session is no longer part of any active process.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }
                assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)


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
                assertThat(cancelled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
                @Suppress("UNCHECKED_CAST")
                assertThat(cancelled.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("logging out of an authenticated channel") {
                then("the channel ends for good and a new one starts a fresh login via the device link") {

                val channelSessionId = registerAndAuthenticate()
                val beforeLogout = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                assertThat(beforeLogout.channel()["state"]).isEqualTo("AUTHENTICATED")

                // Unlike Cancel (which leaves an AUTHENTICATED channel untouched, nothing to cancel),
                // Logout always ends the channel.
                assertThat(deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")).isEqualTo(HttpStatus.NO_CONTENT)

                // The old channelSessionId is dead - GET still resolves it (same key, valid binding),
                // but it stays LOGGED_OUT and reports no next step; it is never silently re-derived.
                val loggedOutChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                assertThat(loggedOutChannel.channel()["state"]).isEqualTo("LOGGED_OUT")
                assertThat(loggedOutChannel["next"]).isNull()
                assertThat(loggedOutChannel.channel()["currentAcr"]).isNull()

                // A brand-new channel on the SAME device (same DPoP key) still recognizes the account via
                // DeviceAccountLink and skips straight to LOGIN instead of a fresh ident-fsc - two active
                // methods (sms, email) means a selection page, not a direct skip.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                assertThat(newChannelSessionId).isNotEqualTo(channelSessionId)
                assertThat(newChannel.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
                @Suppress("UNCHECKED_CAST")
                assertThat(newChannel.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")

                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
                assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

                val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
                assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")


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

                assertThat(deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")).isEqualTo(HttpStatus.NO_CONTENT)

                // The old ident-fsc tool session is no longer part of any active process.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }
                assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)

                // Half-registered isn't a returning user (same rule as plain Cancel, docs/06-ablaeufe.md
                // via ProcessCancellationService): no account was ever fully provisioned, so the new
                // channel starts registration again, not LOGIN.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))


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
                assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)


                }
            }
        }
    }
}

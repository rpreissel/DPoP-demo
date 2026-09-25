package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainAll
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
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                // Get all the way to Identified (account created) before cancelling, to prove the
                // channel doesn't stay half-bound to that account afterwards.
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                val cancelled = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")
                // ChannelState diagram (docs/02-domaenenmodell.md #3): REGISTERING -> ANONYMOUS -> a
                // fresh registration is offered immediately, so the response already shows REGISTERING
                // again; two ident candidates exist, so a selection page is offered (same as the
                // initial channel init).
                cancelled.channel()["state"] shouldBe "REGISTERING"
                cancelled.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                @Suppress("UNCHECKED_CAST")
                // shouldContainAll, not exact: this only cares that identification is offered as a
                // selection page, not which identification methods the catalog happens to have.
                (cancelled.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")

                // The old ident-fsc tool session is gone: it ended the moment it completed.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }
                exception.statusCode shouldBe HttpStatus.NOT_FOUND


                }
            }
        }

        given("a fresh channel") {
            `when`("cancelling mid-login") {
                then("a fresh login attempt is offered") {

                seedRegisteredAccount()
                // Simulate a fresh app session on the same device: new channel, straight to LOGIN via the device link.
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")

                val cancelled = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")
                // LOGIN cancel doesn't force a channel-state change (docs: only REGISTERING/STEP_UP do);
                // the response re-offers candidates from scratch - two active methods (sms, email) now
                // exist, so that's a selection page, not the single auth-sms tool directly.
                cancelled.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                cancelled.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("logging out of an authenticated channel") {
                then("the channel ends for good and a new one starts a fresh login via the device link") {

                val channelSessionId = loginAsSeededAccount()
                val beforeLogout = get("/orchestrator/api/v1/channels/$channelSessionId")
                beforeLogout.channel()["state"] shouldBe "AUTHENTICATED"

                // Interactive logout starts a confirmation journey via POST /logouts.
                val logoutPrompt = post("/orchestrator/api/v1/channels/$channelSessionId/logouts")
                logoutPrompt.channel()["state"] shouldBe "AUTHENTICATED"
                logoutPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")

                // The old channelSessionId is dead - GET still resolves it (same key, valid binding),
                // but it stays LOGGED_OUT and reports no next step; it is never silently re-derived.
                val loggedOutChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
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
                newChannel.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")

                val authenticated = authenticateViaSms(newChannelSessionId)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("a fresh channel") {
            `when`("logging out during active registration") {
                then("the registration process is cancelled too") {

                // Rolled out by hand: this test needs the ident-fsc toolSessionId itself to prove
                // it is dead after the logout.
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                // Direct DELETE logs out without confirmation (non-authenticated channel).
                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId") shouldBe HttpStatus.NO_CONTENT

                // The old ident-fsc tool session is gone: it ended the moment it completed.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }
                exception.statusCode shouldBe HttpStatus.NOT_FOUND

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

                val channelSessionId = loginAsSeededAccount()

                currentBindingKeyRef = "a-completely-different-binding-key"

                val exception = assertThrows<HttpClientErrorException> {
                    deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")
                }
                exception.statusCode shouldBe HttpStatus.FORBIDDEN


                }
            }
        }

        given("an authenticated channel whose refresh window has lapsed (idle)") {
            `when`("the app asks for a token") {
                then("the login is over: 410, the channel ends as EXPIRED, its tokens are discarded (review 2026-09, M-4)") {

                val channelSessionId = loginAsSeededAccount()
                get("/orchestrator/api/v1/channels/$channelSessionId/token")
                val authContextId = jdbcTemplate.queryForObject(
                    "SELECT auth_context_id FROM orchestrator.channel_session WHERE id = ?", java.util.UUID::class.java,
                    java.util.UUID.fromString(channelSessionId)
                )
                jdbcTemplate.update(
                    "UPDATE orchestrator.auth_context SET access_expires_at = DATEADD('SECOND', -10, CURRENT_TIMESTAMP), " +
                        "refresh_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    authContextId
                )

                val gone = assertThrows<HttpClientErrorException> { get("/orchestrator/api/v1/channels/$channelSessionId/token") }
                gone.statusCode shouldBe HttpStatus.GONE
                jdbcTemplate.queryForObject(
                    "SELECT state FROM orchestrator.channel_session WHERE id = ?", String::class.java, java.util.UUID.fromString(channelSessionId)
                ) shouldBe "EXPIRED"
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.auth_context WHERE id = ? AND refresh_token IS NULL AND access_token IS NULL",
                    Int::class.java, authContextId
                ) shouldBe 1


                }
            }
        }

        given("an authenticated channel that is logged out directly (DELETE)") {
            `when`("the logout goes through") {
                then("its RefreshToken is discarded as well, the same way as the confirmed logout (review 2026-09, M-4)") {

                val channelSessionId = loginAsSeededAccount()
                get("/orchestrator/api/v1/channels/$channelSessionId/token")
                val authContextId = jdbcTemplate.queryForObject(
                    "SELECT auth_context_id FROM orchestrator.channel_session WHERE id = ?", java.util.UUID::class.java,
                    java.util.UUID.fromString(channelSessionId)
                )

                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId") shouldBe HttpStatus.NO_CONTENT

                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.auth_context WHERE id = ? AND refresh_token IS NULL",
                    Int::class.java, authContextId
                ) shouldBe 1


                }
            }
        }

        given("an sms user who authenticated and then logged out") {
            `when`("the completed auth-sms PATCH is replayed with the same TAN") {
                then("it is rejected and the channel stays logged out") {

                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                }
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"$tan"}""")
                    .channel()["state"] shouldBe "AUTHENTICATED"

                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId") shouldBe HttpStatus.NO_CONTENT

                assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"$tan"}""")
                }
                get("/orchestrator/api/v1/channels/$channelSessionId").channel()["state"] shouldBe "LOGGED_OUT"


                }
            }
        }

        given("an sms user who just authenticated") {
            `when`("the completed auth-sms PATCH is replayed on the still-open channel") {
                then("the finished tool session is no longer usable") {

                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                }
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"$tan"}""")

                assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"$tan"}""")
                }


                }
            }
        }
    }
}

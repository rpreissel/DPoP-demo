package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe

/**
 * Account deletion's loa2 gate (docs/04-orchestrierung.md, DeleteAccountStrategy): the yes/no
 * confirmation always comes first, then - if the session doesn't already carry loa2 - a step-up,
 * which itself may need to fall back to RE_IDENTIFY when no active method alone reaches loa2.
 */
class DeleteAccountIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("an account authenticated via a single loa1-only factor (sms), no other active method") {
            then("declining the gate's own RE_IDENTIFY (nested under its STEP_UP) must NOT delete the account") {
                // Register with sms only, then bypass the enroll-email Required Action via direct
                // SQL - same technique RequiredActionIntegrationTest uses - to reproduce an account
                // whose only active method is a single loa1 POSSESSION factor: nothing left for a
                // plain step-up to combine with, so DELETE_ACCOUNT's own loa2 gate must go through
                // STEP_UP -> RE_IDENTIFY, exactly the nested-cancel path JourneyService's
                // SUSPENDED-parent handoff has to get right twice in a row (RE_IDENTIFY -> STEP_UP,
                // then STEP_UP -> DELETE_ACCOUNT).
                val channelSessionId = identify()
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
                jdbcTemplate.update("UPDATE channel_session SET state = 'AUTHENTICATED' WHERE channel_session_id = ?", channelSessionId)
                jdbcTemplate.update("UPDATE auth_journey SET lifecycle = 'CONSUMED' WHERE channel_session_id = ?", channelSessionId)

                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM channel_session WHERE channel_session_id = ?",
                    Long::class.java,
                    channelSessionId
                )

                // A fresh channel on the same device logs in via sms alone - this session's own
                // evidence is loa1, POSSESSION only.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                val (loginTan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                // Konto löschen -> confirm -> loa2 gate not satisfied (sms alone is loa1) -> STEP_UP
                // -> no active method reaches loa2 either -> RE_IDENTIFY offered.
                val started = post("/orchestrator/api/v1/channels/$newChannelSessionId/account-deletions")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                val accepted = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"accept"}""")
                accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Abbrechen on RE_IDENTIFY's own confirm. Both nested Cancels resolve up the chain
                // (RE_IDENTIFY -> STEP_UP -> DELETE_ACCOUNT); DELETE_ACCOUNT itself is top-level (no
                // suspended parent of its own), so its own Decision.Cancel falls back to
                // DeleteAccountStrategy.onCancel = AUTHENTICATED directly - deletion never even
                // reaches a lesser reconfirmation fallback (which would have accepted the very
                // sms proof that couldn't reach loa2 in the first place).
                val declined = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                declined.channel()["state"] shouldBe "AUTHENTICATED"
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE id = ?",
                    Int::class.java,
                    accountId
                ) shouldBe 1
            }
        }

        given("an authenticated account deleting itself after fresh reconfirmation") {
            then("the completion response already reports LOGGED_OUT, not AUTHENTICATED") {
                val channelSessionId = registerAndAuthenticate()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM channel_session WHERE channel_session_id = ?",
                    Long::class.java,
                    channelSessionId
                )

                val started = post("/orchestrator/api/v1/channels/$channelSessionId/account-deletions")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")

                val (code, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-email")
                }
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                val completed = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email", """{"code":"$code"}""")

                completed.channel()["state"] shouldBe "LOGGED_OUT"
                completed["next"] shouldBe null
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE id = ?",
                    Int::class.java,
                    accountId
                ) shouldBe 0
            }
        }
    }
}

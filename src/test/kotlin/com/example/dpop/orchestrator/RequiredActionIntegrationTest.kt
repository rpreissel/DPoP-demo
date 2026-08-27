package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/**
 * Registration's Required Actions (docs/04-orchestrierung.md #2, Keycloak's "Required Action"
 * concept): a confirmed email must be in place before REGISTRATION can finish, independent of
 * whether the channel's requiredAcr is already satisfied by other means. Kept as its own small
 * file rather than growing the already-large RegistrationLoginStepUpFlowIntegrationTest.kt.
 */
class RequiredActionIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("registration's required actions") {
        then("Registration reaching the acr floor via sms alone still must enroll email before finishing") {
            val channelSessionId = identify()
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (tan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }

            // sms alone already reaches the default loa1 floor - without the Required Action, this
            // would go straight to AUTHENTICATED.
            val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
            assertThat(afterSms.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))

            val channelMidway = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(channelMidway.channel()["state"]).isEqualTo("REGISTERING")

            enrollEmail(channelSessionId)

            val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(finalChannel.channel()["state"]).isEqualTo("AUTHENTICATED")
            @Suppress("UNCHECKED_CAST")
            assertThat((finalChannel.channel()["activeMethods"] as List<*>).methodNames()).containsExactlyInAnyOrder("sms", "email")
        }
        then("Registration choosing email first already satisfies both required actions in one step") {
            val channelSessionId = identify()

            // Enroll email FIRST (its own maxAcr already reaches the default loa1 floor alone) -
            // both Required Actions (confirmed email, sufficient login method) are satisfied by this
            // single enrollment, so registration finishes immediately - no second forced sms step,
            // proving the order of enrollment doesn't matter, only that both end up satisfied.
            val enrollEmailToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
            val email = "required-action-order-${UUID.randomUUID()}@example.com"
            val (code, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollEmailToolSessionId/enroll-email", """{"email":"$email"}""")
            }
            val enrolled = patch("/orchestrator/api/v1/tools/$enrollEmailToolSessionId/enroll-email", """{"code":"$code"}""")
            assertThat(enrolled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            @Suppress("UNCHECKED_CAST")
            assertThat((finalChannel.channel()["activeMethods"] as List<*>).methodNames()).containsExactlyInAnyOrder("email")
        }
        then("Existing account without confirmed email can still login and add a method via manage methods") {
            // Registration WITHOUT the Required Action gate (simulates an account provisioned before
            // this feature existed, or any other pre-existing state) - directly seed via device-bound
            // enrollment only, skip enroll-email entirely by never activating it.
            val channelSessionId = identify()
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (tan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
            // Registration is now stuck offering enroll-email (by design) - directly flip the account
            // to AUTHENTICATED without it, via SQL, to reproduce a pre-existing account that predates
            // this Required Action (the scope this test guards: a plain login and MANAGE must never
            // retroactively enforce it).
            jdbcTemplate.update("UPDATE channel_session SET state = 'AUTHENTICATED' WHERE channel_session_id = ?", channelSessionId)
            jdbcTemplate.update("UPDATE auth_journey SET lifecycle = 'CONSUMED' WHERE channel_session_id = ?", channelSessionId)

            // A fresh channel on the same device recognizes the account via DeviceAccountLink and logs
            // in via the existing sms method - no email confirmation demanded.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String

            val (loginTan, activation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
            }
            val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            // MANAGE itself always demands loa2 session evidence first (unrelated to Required
            // Actions - ManageAuthMethodsStrategy.REQUIRED_ACR); this session only proved sms
            // (loa1), so it forces a step-up via re-identification first.
            val started = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
            assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
            val stepUpIdentToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            val reIdentified = patch(
                "/orchestrator/api/v1/tools/$stepUpIdentToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            // The step-up sub-journey ends here and the parked wish resumes at once: MANAGE offers
            // enroll-email as a normal (not forced) candidate alongside enroll-device - a selection
            // page, not an automatic skip into enroll-email. enroll-password stays correctly excluded
            // (it still needs a confirmed email, which this account deliberately doesn't have),
            // proving the absent obligation does not quietly waive other, unrelated preconditions.
            assertThat(reIdentified.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(reIdentified.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-email", "enroll-device")
        }
        }
    }
}

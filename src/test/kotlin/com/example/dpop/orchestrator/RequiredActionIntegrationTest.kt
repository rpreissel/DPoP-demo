package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
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
        then("Registration demands the confirmed address before a single method is offered") {
            val channelSessionId = identify()

            // The address comes FIRST, before any enrollment: it is account infrastructure and
            // gates enroll-password, so the journey asks for it straight after the identification.
            val identified = get("/orchestrator/api/v1/channels/$channelSessionId")
            identified.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")

            confirmEmail(channelSessionId)

            // sms alone already reaches the default loa1 floor - the run still doesn't finish here,
            // the password obligation is open.
            enrollSms(channelSessionId)
            val channelMidway = get("/orchestrator/api/v1/channels/$channelSessionId")
            channelMidway.channel()["state"] shouldBe "REGISTERING"

            enrollPassword(channelSessionId)

            val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
            finalChannel.channel()["state"] shouldBe "AUTHENTICATED"
            @Suppress("UNCHECKED_CAST")
            (finalChannel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
        }
        then("Registration discharges both required actions - the address, then the password") {
            val channelSessionId = identify()

            // The address is attested first, which is also what unlocks enroll-password at all
            // (ClaimRequirement(EMAIL, PROVEN)) - that gate, not the order of the remaining
            // choices, is what this scenario shows.
            val email = confirmEmail(channelSessionId)
            enrollSms(channelSessionId)
            enrollPassword(channelSessionId)
            val enrolled = get("/orchestrator/api/v1/channels/$channelSessionId")
            enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

            val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
            @Suppress("UNCHECKED_CAST")
            (finalChannel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
        }
        then("Existing account without confirmed email can still login and add a method via manage methods") {
            // Registration WITHOUT the Required Action gate (simulates an account provisioned before
            // this feature existed, or any other pre-existing state) - the channel simply doesn't
            // support confirm-email, so the mandatory address step is skipped (not blocked) and the
            // run finishes on the sms method alone.
            val channelSessionId = post(
                "/orchestrator/api/v1/app/channels",
                """{"availableTools":["ident-fsc","enroll-sms"]}"""
            ).channel()["channelSessionId"] as String
            reIdentifyViaFsc(channelSessionId)
            val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (tan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
            get("/orchestrator/api/v1/channels/$channelSessionId").channel()["state"] shouldBe "AUTHENTICATED"

            // A fresh channel on the same device recognizes the account via DeviceAccountLink and logs
            // in via the existing sms method - no email confirmation demanded.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String

            val authenticated = authenticateViaSms(newChannelSessionId)
            authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

            // MANAGE itself always demands loa2 session evidence first (unrelated to Required
            // Actions - ManageAuthMethodsStrategy.REQUIRED_ACR); this session only proved sms
            // (loa1), so it forces a step-up via re-identification first.
            val started = triggerEnrollmentStepUp(newChannelSessionId)
            started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
            // Exact candidate computation is unit-tested (ReIdentifyStrategyTest) - here only the
            // real HTTP round trip through the sub-journey matters.
            val accepted = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"accept"}""")
            accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
            val reIdentified = reIdentifyViaFsc(newChannelSessionId)
            // The step-up sub-journey ends here and the parked wish resumes at once: MANAGE offers
            // enroll-email as a normal (not forced) candidate alongside enroll-device - a selection
            // page, not an automatic skip into enroll-email. enroll-password stays correctly excluded
            // (it still needs a confirmed email, which this account deliberately doesn't have),
            // proving the absent obligation does not quietly waive other, unrelated preconditions.
            reIdentified.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
            @Suppress("UNCHECKED_CAST")
            val reIdentifiedOptions = reIdentified.stepData()["options"] as List<String>
            // shouldContainAll, not exact - new enrollment methods elsewhere in the catalog
            // shouldn't force an edit here; enroll-password's exclusion is the point of this test
            // and stays an explicit assertion.
            reIdentifiedOptions shouldContainAll listOf("enroll-device", "enroll-qr")
            reIdentifiedOptions shouldNotContain "enroll-password"
        }
        }
    }
}

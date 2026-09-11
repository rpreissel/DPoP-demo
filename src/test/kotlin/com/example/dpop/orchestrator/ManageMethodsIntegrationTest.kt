package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Managing authentication methods on an already-authenticated channel
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class ManageMethodsIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("reading GET .../methods") {
                then("it matches the channel response's activeMethods") {

                // No account known yet - empty collection, not an error (docs/05-api.md #2).
                val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                @Suppress("UNCHECKED_CAST")
                (get("/orchestrator/api/v1/channels/$freshChannelSessionId/methods")["methods"] as List<*>).shouldBeEmpty()

                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                methods.methodNames() shouldContainExactlyInAnyOrder listOf("sms", "email")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                @Suppress("UNCHECKED_CAST")
                channel.channel()["activeMethods"] as List<Map<String, Any?>> shouldBe methods


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("starting MANAGE on an authenticated channel") {
                then("another method can be added") {

                val channelSessionId = registerAndAuthenticate()

                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                // sms and email already active (email via the REGISTRATION Required Action); password and
                // device are offered - two candidates means a selection page, not a single-candidate skip.
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val startedOptions = started.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; sms/email stay explicit
                // exclusions since they're already active - that's the point of this scenario.
                startedOptions shouldContainAll listOf("enroll-password", "enroll-device", "enroll-qr")
                startedOptions shouldNotContain "enroll-sms"
                startedOptions shouldNotContain "enroll-email"

                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")
                // Finishes immediately after ONE enrollment, regardless of whether some higher floor was
                // reached - unlike the identification path, MANAGE never depends on canAccountReach.
                enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContain "password"


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("starting MANAGE once sms, email and password are all active") {
                then("the last remaining candidate is offered directly") {

                // sms + email already active from registerAndAuthenticate (email via the REGISTRATION
                // Required Action) - only password is missing to match this test's name.
                val channelSessionId = registerAndAuthenticate()
                post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                val enrollPasswordToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")
                post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                val enrollQrToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-qr").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$enrollQrToolSessionId/enroll-qr", "{}")

                // sms, email, password and qr are now active - enroll-device is the one remaining
                // catalog candidate (single-candidate skip goes straight to it; the "nothing left"
                // message is covered once device is also enrolled, see DeviceBindingIntegrationTest).
                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-device", "step" to "enroll")


                }
            }
        }

        given("a fresh channel") {
            `when`("deactivating a method that would drop below the channel's floor") {
                then("it is rejected") {

                // sms (POSSESSION) + email (KNOWLEDGE) alone already reach loa2.
                // Password (KNOWLEDGE) is redundant for the MFA requirement, so deactivating it is allowed.
                // But deactivating EMAIL (the only KNOWLEDGE factor) would drop below loa2, so that's rejected.
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                enrollEmail(channelSessionId)

                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val emailInstanceId = methods.first { it["method"] == "email" }["id"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$emailInstanceId")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("deactivating a method while another still covers the floor") {
                then("it succeeds") {

                // sms+email already active from registerAndAuthenticate (email via the REGISTRATION
                // Required Action) - email alone covers the default loa1 floor, so deactivating sms is safe.
                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val smsInstanceId = methods.first { it["method"] == "sms" }["id"] as String

                delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$smsInstanceId")

                // sms is a candidate again now that it was deactivated - email is already confirmed, so
                // password is ALSO now a valid candidate, hence a selection page rather than a skip.
                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val startedOptions = started.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; email stays an explicit
                // exclusion since it's already active - that's the point of this scenario.
                startedOptions shouldContainAll listOf("enroll-sms", "enroll-password", "enroll-device", "enroll-qr")
                startedOptions shouldNotContain "enroll-email"


                }
            }
        }

        given("a fresh channel") {
            `when`("starting MANAGE with only loa1 session evidence") {
                then("it offers email as the complementary factor for loa2") {

                // Register with fsc+sms in one continuous session (loa2), then simulate a completely
                // fresh app session on the same device: DeviceAccountLink skips straight to LOGIN via
                // auth-sms alone, never re-proving fsc, so this session's own evidence sits at loa1.
                registerAndAuthenticate()
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val (authTan, authActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["currentAcr"] shouldBe "loa1"

                // Email is the enrolled KNOWLEDGE factor complementary to SMS (POSSESSION), so MANAGE
                // can step up through existing authentication methods without re-identification.
                val started = triggerEnrollmentStepUp(newChannelSessionId)
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-email", "step" to "auth")
                val (emailCode, emailActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-email")
                }
                val emailToolSessionId = emailActivation.nextRaw()["toolSessionId"] as String
                val steppedUp = patch(
                    "/orchestrator/api/v1/tools/$emailToolSessionId/auth-email",
                    """{"code":"$emailCode"}"""
                )
                steppedUp.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val steppedUpOptions = steppedUp.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; sms/email stay explicit
                // exclusions since they're already active - that's the point of this scenario.
                steppedUpOptions shouldContainAll listOf("enroll-password", "enroll-device", "enroll-qr")
                steppedUpOptions shouldNotContain "enroll-sms"
                steppedUpOptions shouldNotContain "enroll-email"

                val afterStepUp = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterStepUp.channel()["currentAcr"] shouldBe "loa2"


                }
            }
        }

        given("a fresh channel") {
            `when`("starting MANAGE after proving only SMS") {
                then("it uses the enrolled email before offering re-identification") {

                registerAndAuthenticate()
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val (authTan, authActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")

                val started = post("/orchestrator/api/v1/channels/$newChannelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-email", "step" to "auth")


                }
            }
        }
    }
}

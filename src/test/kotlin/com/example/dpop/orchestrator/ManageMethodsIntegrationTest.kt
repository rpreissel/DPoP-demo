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
        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("reading GET .../methods") {
                then("it matches the channel response's activeMethods") {

                // No account known yet - empty collection, not an error (docs/05-api.md #2).
                val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                @Suppress("UNCHECKED_CAST")
                (get("/orchestrator/api/v1/channels/$freshChannelSessionId/methods")["methods"] as List<*>).shouldBeEmpty()

                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                methods.methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                @Suppress("UNCHECKED_CAST")
                channel.channel()["activeMethods"] as List<Map<String, Any?>> shouldBe methods


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("starting MANAGE on an authenticated channel") {
                then("another method can be added") {

                val channelSessionId = registerAndAuthenticate()

                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                // sms and password already active from the registration; email as a login method
                // and device are offered - two candidates means a selection page, not a skip.
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val startedOptions = started.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; sms/email stay explicit
                // exclusions since they're already active - that's the point of this scenario.
                startedOptions shouldContainAll listOf("enroll-email", "enroll-device", "enroll-qr")
                startedOptions shouldNotContain "enroll-sms"
                startedOptions shouldNotContain "enroll-password"
                startedOptions shouldNotContain "confirm-email"

                // One shot: the address was confirmed during registration, so activating the tool
                // completes it - there is nothing left to prove.
                val enrolled = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email")
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

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("starting MANAGE once sms, password and email are all active") {
                then("the last remaining candidate is offered directly") {

                // sms + password already active from registerAndAuthenticate - email as a LOGIN
                // method is what is still missing (the address itself is confirmed).
                val channelSessionId = registerAndAuthenticate()
                post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email")
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

                // sms (POSSESSION) + password (KNOWLEDGE) reach loa2 together. Deactivating the
                // password - the only KNOWLEDGE factor - would drop the account below the channel's
                // floor, so the self-lockout guard rejects it.
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
                confirmEmail(channelSessionId)
                enrollPassword(channelSessionId)

                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val passwordInstanceId = methods.first { it["method"] == "password" }["id"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$passwordInstanceId")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("deactivating a method while another still covers the floor") {
                then("it succeeds") {

                // sms + password already active from registerAndAuthenticate - password alone
                // covers the default loa1 floor, so deactivating sms is safe.
                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val smsInstanceId = methods.first { it["method"] == "sms" }["id"] as String
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sms.enrollment", Int::class.java) shouldBe 1

                delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$smsInstanceId")

                // Removing a method revokes the credential itself, not just the instance flag: the
                // phone number is gone from the owning module, while the deactivated
                // account.auth_method row stays so account deletion still walks every ref.
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sms.enrollment", Int::class.java) shouldBe 0
                // ...and what only that credential backed is withdrawn (ADR-12): a retraction row,
                // while the claim row itself stays - the log never mutates.
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.retraction WHERE attribute_type = 'phone_number'", Int::class.java
                ) shouldBe 1
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.attribute WHERE attribute_type = 'phone_number'", Int::class.java
                ) shouldBe 1

                // sms is a candidate again now that it was deactivated - email is already confirmed, so
                // password is ALSO now a valid candidate, hence a selection page rather than a skip.
                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val startedOptions = started.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; email stays an explicit
                // exclusion since it's already active - that's the point of this scenario.
                startedOptions shouldContainAll listOf("enroll-sms", "enroll-email", "enroll-device", "enroll-qr")
                startedOptions shouldNotContain "enroll-password"
                startedOptions shouldNotContain "confirm-email"


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

                // Password is the enrolled KNOWLEDGE factor complementary to SMS (POSSESSION), so
                // MANAGE can step up through existing methods without re-identification.
                val started = triggerEnrollmentStepUp(newChannelSessionId)
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")
                val passwordToolSessionId = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
                val steppedUp = patch(
                    "/orchestrator/api/v1/tools/$passwordToolSessionId/auth-password",
                    """{"password":"correct-horse-battery"}"""
                )
                steppedUp.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val steppedUpOptions = steppedUp.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; sms/email stay explicit
                // exclusions since they're already active - that's the point of this scenario.
                steppedUpOptions shouldContainAll listOf("enroll-email", "enroll-device", "enroll-qr")
                steppedUpOptions shouldNotContain "enroll-sms"
                steppedUpOptions shouldNotContain "enroll-password"
                steppedUpOptions shouldNotContain "confirm-email"

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
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")


                }
            }
        }
    }
}

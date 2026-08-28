package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.assertj.core.api.Assertions.assertThat
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
                assertThat((get("/orchestrator/api/v1/app/channels/$freshChannelSessionId/methods")["methods"] as List<*>)).isEmpty()

                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                assertThat(methods.methodNames()).containsExactlyInAnyOrder("sms", "email")

                val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                @Suppress("UNCHECKED_CAST")
                assertThat(channel.channel()["activeMethods"] as List<Map<String, Any?>>).isEqualTo(methods)


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("starting MANAGE on an authenticated channel") {
                then("another method can be added") {

                val channelSessionId = registerAndAuthenticate()

                val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
                // sms and email already active (email via the REGISTRATION Required Action); password and
                // device are offered - two candidates means a selection page, not a single-candidate skip.
                assertThat(started.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
                @Suppress("UNCHECKED_CAST")
                assertThat(started.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-password", "enroll-device")

                val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")
                // Finishes immediately after ONE enrollment, regardless of whether some higher floor was
                // reached - unlike the identification path, MANAGE never depends on canAccountReach.
                assertThat(enrolled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

                val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
                @Suppress("UNCHECKED_CAST")
                assertThat(channel.channel()["currentAmr"] as List<String>).contains("password")


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("starting MANAGE once sms, email and password are all active") {
                then("the last remaining candidate is offered directly") {

                // sms + email already active from registerAndAuthenticate (email via the REGISTRATION
                // Required Action) - only password is missing to match this test's name.
                val channelSessionId = registerAndAuthenticate()
                post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
                val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

                // sms, email and password are now active - enroll-device is the one remaining catalog
                // candidate (single-candidate skip goes straight to it; the "nothing left" message is
                // covered once device is also enrolled, see DeviceBindingIntegrationTest).
                val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
                assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-device", "step" to "enroll"))


                }
            }
        }

        given("a fresh channel") {
            `when`("deactivating a method that would drop below the channel's floor") {
                then("it is rejected") {

                // sms+email alone (both POSSESSION, registerAndAuthenticate's default) can't demonstrate
                // this anymore: email alone already covers the default loa1 floor, so deactivating sms
                // wouldn't drop below it. Use an explicit loa2 channel with sms+email+password instead
                // (same recipe as mfaCombination_smsAndPasswordTogetherReachLoa2Test) - sms/email are both
                // POSSESSION, password is the only KNOWLEDGE factor, so deactivating IT is what breaks the
                // MFA combination the loa2 floor requires.
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                enrollEmail(channelSessionId)
                val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val passwordInstanceId = methods.first { it["method"] == "password" }["id"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    delete("/orchestrator/api/v1/app/channels/$channelSessionId/methods/$passwordInstanceId")
                }
                assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)


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
                val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val smsInstanceId = methods.first { it["method"] == "sms" }["id"] as String

                delete("/orchestrator/api/v1/app/channels/$channelSessionId/methods/$smsInstanceId")

                // sms is a candidate again now that it was deactivated - email is already confirmed, so
                // password is ALSO now a valid candidate, hence a selection page rather than a skip.
                val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
                assertThat(started.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
                @Suppress("UNCHECKED_CAST")
                assertThat(started.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-password", "enroll-device")


                }
            }
        }

        given("a fresh channel") {
            `when`("starting MANAGE with only loa1 session evidence") {
                then("it steps up to loa2 first") {

                // Register with fsc+sms in one continuous session (loa2), then simulate a completely
                // fresh app session on the same device: DeviceAccountLink skips straight to LOGIN via
                // auth-sms alone, never re-proving fsc, so this session's own evidence sits at loa1.
                registerAndAuthenticate()
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val (authTan, authActivation) = captureMockTan {
                    post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
                val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
                assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa1")

                // The account has only sms enrolled - no second AUTH method exists to combine with, so
                // without re-identification this would be a dead end (the bug this test guards against).
                // MANAGE must offer ident-fsc as a way to reach loa2 instead of erroring out.
                val started = triggerEnrollmentStepUp(newChannelSessionId)
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                (started.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("ident-fsc", "ident-eid")

                val reIdentified = reIdentifyViaFsc(newChannelSessionId)
                // Re-identification alone already reaches loa2, so the step-up sub-journey ends - and the
                // ORIGINAL wish resumes right there. The user does not have to ask for the enrollment a
                // second time; that is the whole point of parking the wish rather than replacing it.
                assertThat(reIdentified.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
                @Suppress("UNCHECKED_CAST")
                assertThat(reIdentified.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-password", "enroll-device")

                val afterStepUp = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
                assertThat(afterStepUp.channel()["currentAcr"]).isEqualTo("loa2")


                }
            }
        }

        given("a fresh channel") {
            `when`("re-identifying as a different person during a MANAGE step-up") {
                then("it is rejected") {

                registerAndAuthenticate()
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val (authTan, authActivation) = captureMockTan {
                    post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")

                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
                val identToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                // A different KVNR resolves to a different person/account - must not silently take over
                // this session's account.
                val exception = assertThrows<HttpClientErrorException> {
                    patch(
                        "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                        """{"kvnr":"B987654321","name":"Beispiel","vorname":"Erika","fsc":"ERIKA123"}"""
                    )
                }
                assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)


                }
            }
        }
    }
}

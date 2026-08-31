package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * Logging back in on an already-registered device, device-bound and lookup-based alike
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class LoginFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun linkedAccountsFor(bindingKeyRef: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM device_account_link WHERE binding_key_ref = ?", Int::class.java, bindingKeyRef
        ) ?: 0

    init {
        given("a fresh channel") {
            `when`("identifying again with the same KVNR as an already-registered account") {
                then("auth is offered instead of enrollment") {

                // First full registration: creates an account for KVNR A123456789 with an active sms method.
                registerAndAuthenticate()

                // A brand-new channel (e.g. a different device) identifies with the SAME KVNR -
                // findOrCreateAccount (docs/05-api.md #2) reuses the existing account instead of a second one.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val identified = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )

                // The reused account already has active sms/email methods reaching loa2 - nothing left to
                // enroll. Offer proving one of those existing methods instead of dead-ending (previously:
                // 410 PROCESS_ABORTED, since enrollmentCandidates came back empty -
                // docs/04-orchestrierung.md #1). Two candidates -> a selection page, not a direct skip.
                identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                identified.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")


                }
            }
        }

        given("a fresh channel") {
            `when`("activating the same tool twice in a row") {
                then("the first tool session is cleanly orphaned") {

                registerAndAuthenticate()

                // Simulate a fresh app session and activate auth-sms TWICE (e.g. a double client
                // request) - each activation mints its own ToolSession with its own issued TAN.
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                val firstActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                val firstToolSessionId = firstActivation.nextRaw()["toolSessionId"] as String
                val secondActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                val secondToolSessionId = secondActivation.nextRaw()["toolSessionId"] as String
                secondToolSessionId shouldNotBe firstToolSessionId

                // The first (now superseded) ToolSession is cleanly rejected - not the confusing
                // module-internal "Unknown tool session" error that surfaced before this fix.
                @Suppress("UNCHECKED_CAST")
                val firstTan = (firstActivation["demo"] as Map<String, Any?>)["tan"] as String
                val rejected = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"$firstTan"}""")
                }
                rejected.statusCode shouldBe HttpStatus.CONFLICT

                // The second (current) ToolSession works normally with its own TAN.
                @Suppress("UNCHECKED_CAST")
                val secondTan = (secondActivation["demo"] as Map<String, Any?>)["tan"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"$secondTan"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")


                }
            }
        }

        given("a fresh channel") {
            `when`("one auth method is enrolled, below the channel's own ACR floor") {
                then("the device link is written immediately anyway") {

                // Channel requires loa2, so a single loa1-rated method isn't enough for THIS channel -
                // registration doesn't finish yet, it offers a selection page next (enroll-email/-device).
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
                afterSms.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")

                // Abandon here (never enroll email/password/device, never reach this channel's own loa2
                // floor) -
                // a fresh app session (new channel, plain default loa1 floor) must still recognize this
                // device via the sms method already on file, not fall back to ident-fsc.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")

                val (loginTan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/${newChannel.channel()["channelSessionId"]}/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")


                }
            }
        }

        given("a fresh channel") {
            `when`("re-identifying into an already-enrolled account on a new device") {
                then("the device link is written for that device too") {

                // Register+enroll fully on binding key #1 (writes the DeviceAccountLink for key #1).
                registerAndAuthenticate()

                // Simulate a device whose key isn't linked yet (e.g. a fresh browser profile, or the
                // original key was lost) - the default (no intent) correctly falls back to ident-fsc since key #2 has no
                // link yet.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val reidentified = post("/orchestrator/api/v1/app/channels")
                reidentified.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                val channelSessionId = reidentified.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )

                // The account already has sms enrolled from before, so this offers ordinary auth-sms
                // (not enrollment) - proving it is a ToolOutcome.Completed.Authenticated with
                // outcome.accountId == null (account was already known from the channel, not resolved
                // via lookup). Without also linking here, this device's key #2 would never get a
                // DeviceAccountLink and would be forced back through ident-fsc on every future connect.
                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")

                // A third, brand-new channel on the SAME key #2 must now skip straight to LOGIN too -
                // two active methods (sms, email) means a selection page, not a direct skip.
                val thirdChannel = post("/orchestrator/api/v1/app/channels")
                thirdChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                thirdChannel.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("logging in via auth-password-lookup with email and password") {
                then("it authenticates into the existing account and relinks the device") {

                val email = registerWithEmailAndPassword()

                // Same mocked device, but intent=lookup_login forces lookup-based login regardless of the
                // DeviceAccountLink this device already has from registerWithEmailAndPassword above
                // (docs/04-orchestrierung.md, lookup-based login).
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = loginStart.channel()["channelSessionId"] as String
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")

                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
                val authenticated = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )
                authenticated.next() shouldBe 
                    mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                

                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContain "password"

                // Accepting is what writes DeviceAccountLink - a subsequent FAST channel on this device
                // is then recognized instead of having to identify again.
                val nextAuto = post("/orchestrator/api/v1/app/channels")
                nextAuto.channel()["state"] shouldNotBe "REGISTERING"


                }
            }
        }

        given("a fresh channel") {
            `when`("logging in via auth-sms-lookup with email and TAN") {
                then("it authenticates into the existing account") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (enrollTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
                val email = enrollEmail(channelSessionId)

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val lookupToolSessionId = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String

                val (loginTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"email":"$email"}""")
                }
                val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"tan":"$loginTan"}""")
                // A lookup login does not finish on the proof itself: the device binding is offered
                // explicitly, because this intent is chosen by people who want no device binding.
                authenticated.next() shouldBe 
                    mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                

                val done = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/answer", """{"answer":"accept"}""")
                done.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$lookupChannelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("declining the device-binding offer after a lookup login") {
                then("the device stays unlinked") {

                val email = registerWithEmailAndPassword()

                // A DIFFERENT physical device: it has never been linked, so whether a link exists
                // afterwards is decided purely by the answer to the binding offer.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = loginStart.channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )

                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                linkedAccountsFor(currentBindingKeyRef) shouldBe 0

                // And a plain FAST channel on this device consequently still has to identify.
                val nextAuto = post("/orchestrator/api/v1/app/channels")
                nextAuto.channel()["state"] shouldBe "REGISTERING"


                }
            }
        }

        given("a fresh channel") {
            `when`("reading the channel after logging in via only one of several active methods") {
                then("activeMethods still lists the methods this session never proved") {

                // loa2 up front: default loa1 would already be satisfied by email alone, ending
                // registration (finishAsAuthenticated -> process consumed) before enroll-password could
                // ever be activated (same reasoning as passwordEnrollmentAndSubsequentLoginFlow above).
                val channelSessionId =
                    post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                enrollEmail(channelSessionId)
                val enrollPasswordToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

                val logoutPrompt = post("/orchestrator/api/v1/channels/$channelSessionId/logouts")
                logoutPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")

                // Fresh device-bound LOGIN (same device, DeviceAccountLink still points here): default
                // loa1 floor is satisfied by a single method, so this proves ONLY password.
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val loginChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val authToolSessionId = post("/orchestrator/api/v1/channels/$loginChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$loginChannelSessionId")
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContainExactly listOf("password")
                @Suppress("UNCHECKED_CAST")
                (channel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("email", "password")


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("logging in via auth-email-lookup with email and code") {
                then("it authenticates into the existing account") {

                val email = registerWithEmailAndPassword()

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val lookupToolSessionId = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String

                val (loginCode, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"email":"$email"}""")
                }
                val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"code":"$loginCode"}""")
                authenticated.next() shouldBe 
                    mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                
                post("/orchestrator/api/v1/channels/$lookupChannelSessionId/answer", """{"answer":"accept"}""")

                val channel = get("/orchestrator/api/v1/channels/$lookupChannelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContain "email"


                }
            }
        }

        given("a fresh channel") {
            `when`("submitting an unknown email to a lookup login") {
                then("it fails in exactly the same shape as a wrong credential") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String

                // Same shape as a wrong password against a real account (200, Failed -> retry offered)
                // - never a distinct HTTP error for "unknown email" (enumeration protection).
                val response = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"nobody@example.com","password":"whatever12"}"""
                )
                response.stepData()["error"].shouldNotBeNull()
                response.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password-lookup", "step" to "auth")


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("submitting an unknown vs. a known email to auth-email-lookup") {
                then("the responses are indistinguishable") {

                val knownEmail = registerWithEmailAndPassword()

                fun submit(email: String): Map<String, Any?> {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""").channel()["channelSessionId"] as String
                    val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String
                    return patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email-lookup", """{"email":"$email"}""").next()
                }

                // Identical `next` for both - a different step, toolId or error would reveal whether the
                // address exists. Only the (invisible) mail send and the stored accountId differ.
                val actualNext = submit("nobody@example.com")
                actualNext shouldBe submit(knownEmail)
                actualNext shouldBe mapOf("type" to "tool", "toolId" to "auth-email-lookup", "step" to "codeInput")


                }
            }
        }

        given("a fresh channel") {
            `when`("opening a channel with intent=lookup_login on a device that was never linked") {
                then("lookup tools are offered, not registration") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                @Suppress("UNCHECKED_CAST")
                channelResponse.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")


                }
            }
        }
    }
}

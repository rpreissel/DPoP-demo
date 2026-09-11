package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldNotContainKeys
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * Registration and login of a freshly identified person, via each credential type in turn
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class RegistrationFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a fresh channel") {
            `when`("registering via ident-fsc, enroll-sms and enroll-email, then starting a fresh session") {
                then("the account reaches AUTHENTICATED and the subsequent login succeeds") {

                // 1) Channel init -> registration entry point (docs/05-api.md #2 example 1). Two ident
                // methods are registered (ident-fsc, ident-eid), so a selection page is offered instead
                // of a single-candidate skip.
                val channelResponse = post("/orchestrator/api/v1/app/channels")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                channelResponse.channel()["state"] shouldBe "REGISTERING"
                channelResponse.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                @Suppress("UNCHECKED_CAST")
                // shouldContainAll, not exact: identification is offered, not the catalog's exact set.
                (channelResponse.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")

                // 2) Activate ident-fsc
                val identActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc")
                val identToolSessionId = identActivation.nextRaw()["toolSessionId"] as String
                identActivation.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input")
                @Suppress("UNCHECKED_CAST")
                identActivation.stepData()["missingFields"] as List<String> shouldContainExactlyInAnyOrder listOf("kvnr", "name", "vorname")

                // 3) Supply kvnr/name/vorname -> only fsc missing
                val afterNames = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max"}"""
                )
                @Suppress("UNCHECKED_CAST")
                afterNames.stepData()["missingFields"] as List<String> shouldContainExactly listOf("fsc")

                // 4) Supply the valid FSC -> identified; three enroll candidates exist (sms, email, device),
                // so the process offers a selection page instead of skipping straight to one of them.
                // enroll-password isn't offered yet - it requires a confirmed account email first.
                val identified = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val identifiedOptions = identified.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest: new enrollment methods
                // elsewhere in the catalog don't change this. enroll-password's absence is checked
                // explicitly since it's the one deliberately excluded (unconfirmed email).
                identifiedOptions shouldContainAll listOf("enroll-sms", "enroll-email", "enroll-device", "enroll-qr")
                identifiedOptions shouldNotContain "enroll-password"

                // 5) Activate enroll-sms
                val enrollActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms")
                val enrollToolSessionId = enrollActivation.nextRaw()["toolSessionId"] as String
                @Suppress("UNCHECKED_CAST")
                enrollActivation.stepData()["missingFields"] as List<String> shouldContainExactly listOf("phoneNumber")

                // 6) Supply phone number -> TAN sent (mock); demo mode also echoes it in the response's
                // `demo` object (never stepData - docs/05-api.md #2) so testers don't need server-log
                // access, and both must agree on the same TAN.
                val (enrollTan, afterPhone) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                afterPhone.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput")
                afterPhone.stepData() shouldBe mapOf("missingFields" to listOf("tan"))
                @Suppress("UNCHECKED_CAST")
                (afterPhone["demo"] as Map<String, Any?>)["tan"] shouldBe enrollTan
                // Mid-flow: tool responses never carry currentAcr/currentAmr/activeMethods
                // (docs/05-api.md #2) - not production data any tool step renders.
                afterPhone.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")

                // 7) Confirm TAN -> enrolled, account now reaches loa2 with one factor. Not authenticated
                // yet though: a confirmed email is a Required Action of REGISTRATION
                // (docs/04-orchestrierung.md #2), independent of ACR - single remaining candidate, so the
                // client is skipped straight to enroll-email rather than a selection page.
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
                enrolled.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll")
                enrolled.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")

                // 8) Complete the required enroll-email step -> only now does registration finish.
                enrollEmail(channelSessionId)

                // 9) Channel now reports AUTHENTICATED with fsc+sms+email evidence
                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
                finalChannel.channel()["state"] shouldBe "AUTHENTICATED"
                finalChannel.channel()["currentAcr"] shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                finalChannel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("fsc", "sms", "email")

                // --- Simulate a fresh app session on the SAME device (same DPoP key, no remembered
                // channelSessionId): POST always mints a brand-new channel, but DeviceAccountLink still
                // recognizes this device and routes it straight to LOGIN instead of ident-fsc. Two active
                // methods (sms, email) now exist, so a selection page is offered - pick auth-sms. ---
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                newChannelSessionId shouldNotBe channelSessionId
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")

                val (authTan, authActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String

                val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("an identified channel") {
            `when`("submitting an invalid phone number to enroll-sms") {
                then("it is rejected as bad request") {

                val channelSessionId = identify()
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"not-a-number"}""")
                }
                exception.statusCode shouldBe HttpStatus.BAD_REQUEST


                }
            }
        }

        given("the following setup") {
            `when`("creating a channel") {
                then("the response is 201 with a Location header pointing at it") {

                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                    HttpEntity("""{"availableTools":["ident-fsc"]}""", headers()), mapType
                )
                response.statusCode shouldBe HttpStatus.CREATED
                val channelSessionId = response.body!!.channel()["channelSessionId"] as String
                response.headers.location.toString() shouldBe "http://localhost:$port/orchestrator/api/v1/channels/$channelSessionId"


                }
            }
        }

        given("an identified channel") {
            `when`("activating a tool") {
                then("the response is 201 with a Location header pointing at the tool resource") {

                val channelSessionId = identify()
                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms",
                    HttpMethod.POST, HttpEntity("{}", headers()), mapType
                )
                response.statusCode shouldBe HttpStatus.CREATED
                val toolSessionId = response.body!!.nextRaw()["toolSessionId"] as String
                response.headers.location.toString() shouldBe "http://localhost:$port/orchestrator/api/v1/tools/$toolSessionId/enroll-sms"


                }
            }
        }

        given("an identified channel") {
            `when`("resuming mid enroll-sms via GET") {
                then("the running tool session is reused, not a second TAN sent") {

                // Get to right after phoneNumber was submitted (TAN already sent, awaiting tanInput) -
                // the exact point where an app restart used to reactivate enroll-sms and send a second TAN.
                val channelSessionId = identify()
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (enrollTan, afterPhone) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                afterPhone.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput")

                // Simulate "Sitzung fortsetzen" (resume, docs/05-api.md #2): the guaranteed resume entry
                // point is GET, never a reactivation. It must hand back the SAME toolSessionId that's
                // already awaiting the TAN, not a fresh one.
                val resumed = get("/orchestrator/api/v1/channels/$channelSessionId")
                resumed.nextRaw() shouldBe 
                    mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput", "toolSessionId" to enrollToolSessionId)
                

                // The TAN captured before "resume" still confirms the SAME session - proving no second
                // TAN was needed and the phoneNumber already entered wasn't discarded. Registration isn't
                // finished yet though: confirmed email is still an outstanding Required Action.
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
                enrolled.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll")


                }
            }
        }

        given("a fresh channel") {
            `when`("exhausting the ident-fsc retry budget") {
                then("the process ends as 410 Gone") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max"}"""
                )

                // First few wrong attempts stay retryable (200 + error in stepData, not an HTTP error).
                repeat(2) {
                    val retryResponse = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"WRONGCODE"}""")
                    retryResponse.stepData()["error"].shouldNotBeNull()
                    retryResponse.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input")
                }

                // Retry limit (3) exhausted -> process aborted, 410 Gone.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"WRONGCODE"}""")
                }
                exception.statusCode shouldBe HttpStatus.GONE


                }
            }
        }

        given("a fresh channel") {
            `when`("a different DPoP key claims to own the channel") {
                then("access is forbidden") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                // A different DPoP key now claims to own this channelSessionId.
                currentBindingKeyRef = "a-completely-different-binding-key"

                val exception = assertThrows<HttpClientErrorException> {
                    get("/orchestrator/api/v1/channels/$channelSessionId")
                }
                exception.statusCode shouldBe HttpStatus.FORBIDDEN


                }
            }
        }

        given("a fresh channel") {
            `when`("registering via ident-fsc, enroll-sms and enroll-email, then starting a fresh session") {
                then("the account reaches AUTHENTICATED and the subsequent login succeeds") {

                // 1) Identify, then enroll SMS and confirm email. The loa2 channel requires both
                // complementary loa1 factors before registration can finish.
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                // Enroll sms (POSSESSION) and email (KNOWLEDGE) for loa2 without re-identification
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                enrollEmail(channelSessionId)

                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
                finalChannel.channel()["state"] shouldBe "AUTHENTICATED"
                finalChannel.channel()["currentAcr"] shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                finalChannel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("fsc", "sms", "email")

                // --- Simulate a fresh app session on the same device ---
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                // Neither sms nor email alone reaches loa2 (each only loa1) - the login offers a pick between both.
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")

                val (authTan, smsActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-sms")
                }
                val authToolSessionId = smsActivation.nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")

                // SMS completed the default loa1 login. Start an explicit step-up before using
                // email as the distinct KNOWLEDGE factor for loa2.
                post("/orchestrator/api/v1/channels/$newChannelSessionId/step-ups", """{"requiredAcr":"loa2"}""")
                val (emailTan, emailActivation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-email")
                }
                val authEmailToolSessionId = emailActivation.nextRaw()["toolSessionId"] as String

                // 3) Wrong email tan first - retryable, not an HTTP error.
                val retry = patch(
                    "/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-email",
                    """{"code":"wrong-code"}"""
                )
                retry.stepData()["error"].shouldNotBeNull()
                retry.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-email", "step" to "auth")

                // 4) Correct email tan -> completes MFA authentication with sms+email combination reaching loa2.
                val authenticated = patch(
                    "/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-email",
                    """{"code":"$emailTan"}"""
                )
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("a fresh channel") {
            `when`("enrolling a password shorter than the minimum length") {
                then("it is rejected as bad request") {

                // loa2 up front: default loa1 would already be satisfied by email alone, ending
                // registration (finishAsAuthenticated -> process consumed) before enroll-password could
                // ever be activated.
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                enrollEmail(channelSessionId)
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password", """{"password":"short"}""")
                }
                exception.statusCode shouldBe HttpStatus.BAD_REQUEST


                }
            }
        }

        given("an identified channel") {
            `when`("activating enroll-password before the account has a confirmed email") {
                then("it is rejected as conflict") {

                // Bypass attempt: activate enroll-password directly while still in an "enrollment"
                // selection context, without ever having confirmed an email - validateActivation alone
                // only checks the CATEGORY (ENROLL) matches, not that this specific toolId was actually
                // offered, so ToolControllerSupport must enforce the requiresConfirmedEmail precondition
                // itself, not just rely on it being excluded from stepData.options.
                val channelSessionId = identify()

                val exception = assertThrows<HttpClientErrorException> {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT


                }
            }
        }

        given("a fresh channel") {
            `when`("opening a channel with intent=register on an already-linked device") {
                then("a fresh registration starts instead of login") {

                registerAndAuthenticate()

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")
                channelResponse.channel()["state"] shouldBe "REGISTERING"
                channelResponse.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")


                }
            }
        }

        given("the following setup") {
            `when`("a request arrives without a DPoP header") {
                then("it is rejected as unauthorized before any controller logic runs") {

                val headersWithoutDpop = HttpHeaders().apply { set("Content-Type", "application/json") }
                val exception = assertThrows<HttpClientErrorException> {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                        HttpEntity("{}", headersWithoutDpop), mapType
                    )
                }
                exception.statusCode shouldBe HttpStatus.UNAUTHORIZED


                }
            }
        }
    }
}

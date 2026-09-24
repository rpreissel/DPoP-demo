package com.example.dpop.orchestrator

import com.example.dpop.texts.templateOf
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import org.springframework.beans.factory.annotation.Autowired
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

    @Autowired
    private lateinit var accountService: AccountService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a fresh channel") {
            `when`("registering via ident-fsc, confirm-email, enroll-sms and enroll-password, then starting a fresh session") {
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
                identActivation.stepData()["missingFields"] as List<String> shouldContainExactlyInAnyOrder listOf("kvnr", "name", "vorname", "geburtsdatum")

                // 3) Supply kvnr/name/vorname/geburtsdatum -> only fsc missing
                val afterNames = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15"}"""
                )
                @Suppress("UNCHECKED_CAST")
                afterNames.stepData()["missingFields"] as List<String> shouldContainExactly listOf("fsc")

                // 4) Supply the valid FSC -> identified. The address obligation comes first, before
                // any method is offered: it is account infrastructure, and enroll-password is gated
                // on it. Single candidate, so the client is skipped straight into confirm-email.
                val identified = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                identified.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")

                // 5) Confirm the address -> only NOW are login methods offered, and enroll-password
                // is among them: the obligation that unlocks it is already discharged.
                confirmEmail(channelSessionId)
                val afterEmail = get("/orchestrator/api/v1/channels/$channelSessionId")
                afterEmail.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val enrollOptions = afterEmail.stepData()["options"] as List<String>
                // shouldContainAll (not exact): new enrollment methods elsewhere in the catalog
                // don't change this. enroll-password's presence is asserted explicitly - before the
                // confirmation it is not even a candidate (see the dedicated 409 test below).
                enrollOptions shouldContainAll listOf("enroll-sms", "enroll-device", "enroll-qr", "enroll-password")

                // 6) Activate enroll-sms
                val enrollActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms")
                val enrollToolSessionId = enrollActivation.nextRaw()["toolSessionId"] as String
                @Suppress("UNCHECKED_CAST")
                enrollActivation.stepData()["missingFields"] as List<String> shouldContainExactly listOf("phoneNumber")

                // 7) Supply phone number -> TAN sent (mock); demo mode also echoes it in the response's
                // `demo` object (never stepData - docs/05-api.md #2) so testers don't need server-log
                // access, and both must agree on the same TAN.
                val (enrollTan, afterPhone) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                afterPhone.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput")
                // `@t` names the shape (tool_spi/StepData.kt). enroll-sms says nothing beyond
                // "this input is still missing", so it uses the shared MissingFields shape rather
                // than one of its own.
                afterPhone.stepData() shouldBe mapOf("kind" to "missing-fields", "missingFields" to listOf("tan"))
                @Suppress("UNCHECKED_CAST")
                (afterPhone["demo"] as Map<String, Any?>)["tan"] shouldBe enrollTan
                // Mid-flow: tool responses never carry currentAcr/currentAmr/activeMethods
                // (docs/05-api.md #2) - not production data any tool step renders.
                afterPhone.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")

                // 8) Confirm TAN -> enrolled, account now reaches loa2 with one factor. Not
                // authenticated yet though: the password is the third obligation on every channel,
                // since confirming an address no longer leaves a knowledge method behind.
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
                enrolled.nextRaw()["toolId"] shouldBe "enroll-password"
                enrolled.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")

                enrollPassword(channelSessionId)

                // 9) Channel now reports AUTHENTICATED with fsc+sms+email evidence
                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
                finalChannel.channel()["state"] shouldBe "AUTHENTICATED"
                finalChannel.channel()["currentAcr"] shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                finalChannel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("fsc", "sms", "password")

                // --- Simulate a fresh app session on the SAME device (same DPoP key, no remembered
                // channelSessionId): POST always mints a brand-new channel, but DeviceAccountLink still
                // recognizes this device and routes it straight to LOGIN instead of ident-fsc. Two active
                // methods (sms, email) now exist, so a selection page is offered - pick auth-sms. ---
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                newChannelSessionId shouldNotBe channelSessionId
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")

                val authenticated = authenticateViaSms(newChannelSessionId)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("an identified channel") {
            `when`("submitting an invalid phone number to enroll-sms") {
                then("it is rejected as bad request") {

                val channelSessionId = identifyAndConfirmEmail()
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

                val channelSessionId = identifyAndConfirmEmail()
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
                val channelSessionId = identifyAndConfirmEmail()
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
                // finished yet though: the password is still an outstanding Required Action.
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
                enrolled.nextRaw()["toolId"] shouldBe "enroll-password"


                }
            }
        }

        given("a fresh channel") {
            `when`("ident-fsc gets personal data that does not match the register") {
                then("it is rejected right away, before any code is asked for, and asked for again") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                val rejected = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-16"}"""
                )
                templateOf(rejected.stepData()["error"]) shouldBe "Die Angaben passen zu keiner versicherten Person"
                rejected.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input")

                @Suppress("UNCHECKED_CAST")
                get("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc").stepData()["missingFields"] as List<String> shouldContainExactly
                    listOf("kvnr", "name", "vorname", "geburtsdatum")

                val corrected = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15"}"""
                )
                @Suppress("UNCHECKED_CAST")
                corrected.stepData()["missingFields"] as List<String> shouldContainExactly listOf("fsc")

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
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15"}"""
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
            `when`("enrolling a password shorter than the minimum length") {
                then("it is rejected as bad request") {

                // loa2 up front: default loa1 would already be satisfied by email alone, ending
                // registration (finishAsAuthenticated -> process consumed) before enroll-password could
                // ever be activated.
                val channelSessionId = identifyAndConfirmEmail(requiredAcr = "loa2")
                enrollSms(channelSessionId)
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
                // offered, so ToolControllerSupport must enforce the requires precondition
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

                seedRegisteredAccount()
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

        given("a channel that already identified as one real, credentialed account, then declined its only auth method") {
            `when`("re-identifying via ident-fsc as a SECOND, different real, credentialed account") {
                then("it is rejected as a conflict, the journey stays on the first account") {

                val first = accountFixtures.seedAccount(
                    kvnr = "A123456789", name = "Muster", vorname = "Max",
                    email = "max.muster@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )
                accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )

                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                val firstIdentToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val afterFirstIdent = patch(
                    "/orchestrator/api/v1/tools/$firstIdentToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15","fsc":"VALIDCODE"}"""
                )
                // Max's account already has sms and a confirmed email - AuthChoice, not Enrolling.
                afterFirstIdent.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                val afterFirstIdentRaw = afterFirstIdent.nextRaw()
                val authSmsToolSessionId = (afterFirstIdentRaw["toolSessionId"] as? String)
                    ?: post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String

                // Decline the only offered auth tool - RegisterStrategy.afterAuthDeclined falls
                // back to offerIdentification, re-entering Identifying with an account (Max's)
                // already bound to this journey.
                val afterDecline = delete("/orchestrator/api/v1/tools/$authSmsToolSessionId/auth-sms")
                @Suppress("UNCHECKED_CAST")
                (afterDecline.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")

                val secondIdentToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    patch(
                        "/orchestrator/api/v1/tools/$secondIdentToolSessionId/ident-fsc",
                        """{"kvnr":"B987654321","name":"Beispiel","vorname":"Erika","geburtsdatum":"1990-11-02","fsc":"ERIKA123"}"""
                    )
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                // Neither account was touched by the rejected second identification.
                accountService.findAccount(first)!!.activeAuthenticationMethods.map { it.method }.toSet() shouldBe setOf("sms")

                }
            }
        }
    }
}

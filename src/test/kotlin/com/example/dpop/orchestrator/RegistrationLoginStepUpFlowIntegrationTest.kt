package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.nimbusds.jose.jwk.JWK
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID

/**
 * Exercises the full REGISTRATION (ident-fsc -> enroll-sms -> authenticated), LOGIN (auth-sms),
 * Cancel/Logout and Back/Switch flows end to end against the real HTTP layer, matching
 * docs/05-api.md and docs/06-ablaeufe.md. DPoP validation is mocked (the crypto itself is
 * covered separately); everything downstream - orchestration, policy, persistence - is real.
 *
 * Channel resolution model (docs/02-domaenenmodell.md #3): the DPoP key only proves which
 * DEVICE is talking - `POST .../channels` always mints a brand-new ChannelSession, never
 * resumes one by key. "Same device, fresh app session" is simulated throughout by simply
 * calling `POST .../channels` again with the SAME mocked binding key and capturing the NEW
 * channelSessionId it returns; DeviceAccountLink is what still routes that new channel straight
 * to LOGIN for an already-registered device.
 *
 * The DB is wiped (mutable tables only; person/fsc_code seed data survives) before every test
 * method, so every test can use the same test person without cross-test interference.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegistrationLoginStepUpFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var dpopValidator: DpopValidator

    @MockitoBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // The JDK's default request factory can't send PATCH; HttpClient5 (already a test dep) can.
    private val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    @BeforeEach
    fun resetDatabase() {
        // Children first (FK order); person/fsc_code seed data is left untouched.
        listOf(
            "id_fsc_tool_data", "enroll_sms_tool_data", "auth_sms_use_tool_data",
            "enroll_password_tool_data", "auth_password_use_tool_data",
            "enroll_email_tool_data", "auth_email_use_tool_data",
            "auth_sms_lookup_tool_data", "auth_password_lookup_tool_data",
            "tool_session", "process_session", "session_event",
            "channel_session", "auth_context", "account", "auth_sms", "auth_password",
            "device_account_link", "login_attempt_throttle"
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    @BeforeEach
    fun resetMocks() {
        val fakeJwk = mock(JWK::class.java)
        `when`(dpopValidator.validate(anyString(), anyString(), anyString())).thenAnswer {
            DpopProof(
                token = "mock-token",
                publicKey = fakeJwk,
                jti = UUID.randomUUID().toString(),
                htm = "POST",
                htu = "http://localhost/mock",
                issuedAt = Instant.now(),
                nonce = null
            )
        }
        currentBindingKeyRef = "binding-" + UUID.randomUUID()
        `when`(jwkThumbprintService.computeThumbprint(fakeJwk)).thenAnswer { currentBindingKeyRef }
    }

    private var currentBindingKeyRef: String = ""

    private fun headers(): HttpHeaders = HttpHeaders().apply {
        set("DPoP", "mock-dpop-token")
        set("Content-Type", "application/json")
    }

    private fun post(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, headers()), MAP_TYPE
        ).let { assertThat(it.statusCode.is2xxSuccessful).isTrue(); it.body!! }

    private fun patch(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), MAP_TYPE
        ).let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    private fun get(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.GET, HttpEntity<Void>(headers()), MAP_TYPE
        ).let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    private fun delete(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), MAP_TYPE
        ).let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    /** Logout returns 204 No Content (docs/05-api.md), no body to parse. */
    private fun deleteNoContent(url: String): HttpStatus =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), Void::class.java
        ).statusCode as HttpStatus

    /**
     * `toolSessionId` (docs/05-api.md #2) is stripped here so the many existing exact-map
     * assertions below stay focused on routing (type/toolId|context/step) without each needing
     * to know the concrete session id; use [nextRaw] where the id itself is under test.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.next(): Map<String, Any?> = (this["next"] as Map<String, Any?>).minus("toolSessionId")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nextRaw(): Map<String, Any?> = this["next"] as Map<String, Any?>

    /** The channel-level block every response carries now (docs/05-api.md #2: unified envelope). */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.channel(): Map<String, Any?> = this["channel"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.stepData(): Map<String, Any?> = this["stepData"] as Map<String, Any?>

    /** Mock SMS is only printed to stdout (docs/05-api.md: TAN never appears in the response). */
    private fun captureMockTan(block: () -> Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val response = try {
            block()
        } finally {
            System.setOut(original)
        }
        val printed = buffer.toString()
        // Matches both "[MOCK SMS] TAN 123456 an ..." and "[MOCK EMAIL] Code 123456 an ...".
        val tan = Regex("""(?:TAN|Code) (\d{6}) an""").find(printed)?.groupValues?.get(1)
            ?: error("No mock TAN/code found in captured output: $printed")
        return tan to response
    }

    /** Runs ident-fsc through to Identified using the standard test person, returns the channelSessionId. */
    private fun identify(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        return channelSessionId
    }

    /** Runs ident-fsc + enroll-sms through to AUTHENTICATED, returns the channelSessionId. */
    private fun registerAndAuthenticate(): String {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        return channelSessionId
    }

    @Test
    fun registrationEnrollmentAndSubsequentLoginFlow() {
        // 1) Channel init -> registration entry point (docs/05-api.md #2 example 1). Exactly one
        // ident method is registered, so the selection page is skipped straight to the tool
        // (same skip-if-single-candidate rule as ENROLL/AUTH, docs/04-orchestrierung.md #1).
        val channelResponse = post("/orchestrator/api/v1/app/channels")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        assertThat(channelResponse.channel()["state"]).isEqualTo("REGISTERING")
        assertThat(channelResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        // 2) Activate ident-fsc
        val identActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc")
        val identToolSessionId = identActivation.nextRaw()["toolSessionId"] as String
        assertThat(identActivation.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        @Suppress("UNCHECKED_CAST")
        assertThat(identActivation.stepData()["missingFields"] as List<String>).containsExactlyInAnyOrder("kvnr", "name", "vorname")

        // 3) Supply kvnr/name/vorname -> only fsc missing
        val afterNames = patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max"}"""
        )
        @Suppress("UNCHECKED_CAST")
        assertThat(afterNames.stepData()["missingFields"] as List<String>).containsExactly("fsc")

        // 4) Supply the valid FSC -> identified; two enroll candidates exist (sms, email), so
        // the process offers a selection page instead of skipping straight to one of them.
        // enroll-password isn't offered yet - it requires a confirmed account email first.
        val identified = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
        assertThat(identified.next()).isEqualTo(mapOf("type" to "flow", "context" to "enrollment", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(identified.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-email")

        // 5) Activate enroll-sms
        val enrollActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms")
        val enrollToolSessionId = enrollActivation.nextRaw()["toolSessionId"] as String
        @Suppress("UNCHECKED_CAST")
        assertThat(enrollActivation.stepData()["missingFields"] as List<String>).containsExactly("phoneNumber")

        // 6) Supply phone number -> TAN sent (mock); demo mode also echoes it in the response's
        // `demo` object (never stepData - docs/05-api.md #2) so testers don't need server-log
        // access, and both must agree on the same TAN.
        val (enrollTan, afterPhone) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        assertThat(afterPhone.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput"))
        assertThat(afterPhone.stepData()).isEqualTo(mapOf("missingFields" to listOf("tan")))
        @Suppress("UNCHECKED_CAST")
        assertThat((afterPhone["demo"] as Map<String, Any?>)["tan"]).isEqualTo(enrollTan)
        // Mid-flow: tool responses never carry currentAcr/currentAmr/activeMethods
        // (docs/05-api.md #2) - not production data any tool step renders.
        assertThat(afterPhone.channel()).doesNotContainKeys("currentAcr", "currentAmr", "activeMethods")

        // 7) Confirm TAN -> enrolled, account now reaches loa2 with one factor -> authenticated.
        // Even the response that settles `next` into authenticated stays without account fields -
        // they're only ever reported by the real channel resource (GET below), fetched on demand.
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))
        assertThat(enrolled.channel()).doesNotContainKeys("currentAcr", "currentAmr", "activeMethods")

        // 8) Channel now reports AUTHENTICATED with fsc+sms evidence
        val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(finalChannel.channel()["state"]).isEqualTo("AUTHENTICATED")
        assertThat(finalChannel.channel()["currentAcr"]).isEqualTo("loa2")
        @Suppress("UNCHECKED_CAST")
        assertThat(finalChannel.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "sms")

        // --- Simulate a fresh app session on the SAME device (same DPoP key, no remembered
        // channelSessionId): POST always mints a brand-new channel, but DeviceAccountLink still
        // recognizes this device and routes it straight to LOGIN instead of ident-fsc. ---
        val loginStart = post("/orchestrator/api/v1/app/channels")
        val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
        assertThat(newChannelSessionId).isNotEqualTo(channelSessionId)
        assertThat(loginStart.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))

        val (authTan, authActivation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
        }
        val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String

        val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")
    }

    @Test
    fun invalidPhoneNumber_isRejectedAsBadRequest() {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"not-a-number"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun createChannel_returns201WithLocationPointingAtTheNewChannel() {
        val response = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST, HttpEntity("{}", headers()), MAP_TYPE
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val channelSessionId = response.body!!.channel()["channelSessionId"] as String
        assertThat(response.headers.location.toString())
            .isEqualTo("http://localhost:$port/orchestrator/api/v1/app/channels/$channelSessionId")
    }

    @Test
    fun toolActivation_returns201WithLocationPointingAtTheToolResource() {
        val channelSessionId = identify()
        val response = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms",
            HttpMethod.POST, HttpEntity("{}", headers()), MAP_TYPE
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val toolSessionId = response.body!!.nextRaw()["toolSessionId"] as String
        assertThat(response.headers.location.toString())
            .isEqualTo("http://localhost:$port/orchestrator/api/v1/tools/$toolSessionId/enroll-sms")
    }

    @Test
    fun resumeMidEnrollSms_reusesTheRunningToolSession_insteadOfSendingASecondTan() {
        // Get to right after phoneNumber was submitted (TAN already sent, awaiting tanInput) -
        // the exact point where an app restart used to reactivate enroll-sms and send a second TAN.
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (enrollTan, afterPhone) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        assertThat(afterPhone.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput"))

        // Simulate "Sitzung fortsetzen" (resume, docs/05-api.md #2): the guaranteed resume entry
        // point is GET, never a reactivation. It must hand back the SAME toolSessionId that's
        // already awaiting the TAN, not a fresh one.
        val resumed = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(resumed.nextRaw()).isEqualTo(
            mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput", "toolSessionId" to enrollToolSessionId)
        )

        // The TAN captured before "resume" still confirms the SAME session - proving no second
        // TAN was needed and the phoneNumber already entered wasn't discarded.
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))
    }

    @Test
    fun exhaustedRetries_endTheProcessAsGone() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max"}"""
        )

        // First few wrong attempts stay retryable (200 + error in stepData, not an HTTP error).
        repeat(2) {
            val retryResponse = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"WRONGCODE"}""")
            assertThat(retryResponse.stepData()["error"]).isNotNull()
            assertThat(retryResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        }

        // Retry limit (3) exhausted -> process aborted, 410 Gone.
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"WRONGCODE"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.GONE)
    }

    @Test
    fun bindingKeyMismatch_isForbidden() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

        // A different DPoP key now claims to own this channelSessionId.
        currentBindingKeyRef = "a-completely-different-binding-key"

        val exception = assertThrows<HttpClientErrorException> {
            get("/orchestrator/api/v1/app/channels/$channelSessionId")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun stepUpToUnreachableLevel_abortsTheProcess() {
        // Reuse the happy path up to AUTHENTICATED at loa2 with a single, already-used sms factor.
        val channelSessionId = registerAndAuthenticate()

        // loa3 requires two distinct factor types; this account only ever proves POSSESSION.
        val exception = assertThrows<HttpClientErrorException> {
            post("/orchestrator/api/v1/app/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.GONE)
    }

    @Test
    fun cancelDuringRegistration_resetsAndOffersAFreshStart() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        // Get all the way to Identified (account created) before cancelling, to prove the
        // channel doesn't stay half-bound to that account afterwards.
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )

        val cancelled = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
        // ChannelState diagram (docs/02-domaenenmodell.md #3): REGISTERING -> ANONYMOUS -> a
        // fresh registration is offered immediately, so the response already shows REGISTERING
        // again; single-candidate skip goes straight to the tool (same as the initial channel init).
        assertThat(cancelled.channel()["state"]).isEqualTo("REGISTERING")
        assertThat(cancelled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        // The old ident-fsc tool session is no longer part of any active process.
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun cancelDuringLogin_offersAFreshLoginAttempt() {
        registerAndAuthenticate()

        // Simulate a fresh app session on the same device: new channel, straight to LOGIN via the device link.
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")

        val cancelled = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
        // LOGIN cancel doesn't force a channel-state change (docs: only REGISTERING/STEP_UP do);
        // the response offers the same login attempt again.
        assertThat(cancelled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))
    }

    @Test
    fun reidentifyingAnAlreadyProvisionedAccount_offersAuthInsteadOfEnrollment() {
        // First full registration: creates an account for KVNR A123456789 with an active sms method.
        registerAndAuthenticate()

        // A brand-new channel (e.g. a different device) identifies with the SAME KVNR -
        // findOrCreateAccount (docs/05-api.md #2) reuses the existing account instead of a second one.
        currentBindingKeyRef = "binding-" + UUID.randomUUID()
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        val identified = patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )

        // The reused account already has an active sms method reaching loa2 - nothing left to
        // enroll. Offer proving that existing method instead of dead-ending (previously: 410
        // PROCESS_ABORTED, since enrollmentCandidates came back empty - docs/04-orchestrierung.md #1).
        assertThat(identified.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))
    }

    @Test
    fun duplicateActivation_orphansThePreviousToolSessionCleanly() {
        registerAndAuthenticate()

        // Simulate a fresh app session and activate auth-sms TWICE (e.g. a double client
        // request) - each activation mints its own ToolSession with its own issued TAN.
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

        val firstActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
        val firstToolSessionId = firstActivation.nextRaw()["toolSessionId"] as String
        val secondActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
        val secondToolSessionId = secondActivation.nextRaw()["toolSessionId"] as String
        assertThat(secondToolSessionId).isNotEqualTo(firstToolSessionId)

        // The first (now superseded) ToolSession is cleanly rejected - not the confusing
        // module-internal "Unknown tool session" error that surfaced before this fix.
        @Suppress("UNCHECKED_CAST")
        val firstTan = (firstActivation["demo"] as Map<String, Any?>)["tan"] as String
        val rejected = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"$firstTan"}""")
        }
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)

        // The second (current) ToolSession works normally with its own TAN.
        @Suppress("UNCHECKED_CAST")
        val secondTan = (secondActivation["demo"] as Map<String, Any?>)["tan"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"$secondTan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))
    }

    @Test
    fun switchAwayFromIdentTool_cancelsTheWholeProcess() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

        // No prior step to go "back" to for the first (IDENT) tool - equivalent to Cancel.
        // Single-candidate skip goes straight back to the tool (same as the initial channel init).
        val result = delete("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc")
        assertThat(result.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(channel.channel()["state"]).isEqualTo("REGISTERING")
    }

    @Test
    fun switchAwayFromEnrollTool_reoffersEnrollmentCandidates() {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

        // Two enrollment methods are actually offerable at this point (enroll-password needs a
        // confirmed email first), so switching away re-offers the selection page - but the OLD
        // tool session is abandoned either way.
        val result = delete("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms")
        assertThat(result.next()).isEqualTo(mapOf("type" to "flow", "context" to "enrollment", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(result.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-email")

        // The abandoned tool session is gone even though we re-activate the same toolId.
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        // Re-activating works fine and mints a new tool session.
        val reactivated = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms")
        assertThat(reactivated.nextRaw()["toolSessionId"]).isNotEqualTo(enrollToolSessionId)
    }

    @Test
    fun passwordEnrollmentAndSubsequentLoginFlow() {
        // 1) Identify, confirm email (password's precondition), then enroll password. Channel
        // requires loa2 up front so registration doesn't auto-finish after email alone (which,
        // like sms, only reaches loa1 by itself) before password is ever offered.
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        enrollEmail(channelSessionId)
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String

        // 2) Password alone in one call - the credential is self-verifying, no confirmation handshake.
        val enrolled = patch(
            "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password",
            """{"password":"correct-horse-battery"}"""
        )
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(finalChannel.channel()["state"]).isEqualTo("AUTHENTICATED")
        assertThat(finalChannel.channel()["currentAcr"]).isEqualTo("loa2")
        @Suppress("UNCHECKED_CAST")
        assertThat(finalChannel.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "email", "password")

        // --- Simulate a fresh app session on the same device ---
        val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
        // Neither email nor password alone reaches loa2 - the login offers a pick between both.
        assertThat(loginStart.next()).isEqualTo(mapOf("type" to "flow", "context" to "auth", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-email", "auth-password")

        val authActivation = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-password")
        val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String

        // 3) Wrong password first - retryable, not an HTTP error.
        val retry = patch(
            "/orchestrator/api/v1/tools/$authToolSessionId/auth-password",
            """{"password":"wrong-password"}"""
        )
        assertThat(retry.stepData()["error"]).isNotNull()
        assertThat(retry.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth"))

        // 4) Correct password -> completes password, but loa2 needs the second (differently-typed) factor too.
        val afterPassword = patch(
            "/orchestrator/api/v1/tools/$authToolSessionId/auth-password",
            """{"password":"correct-horse-battery"}"""
        )
        assertThat(afterPassword.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-email", "step" to "auth"))

        val (code, activation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-email")
        }
        val authEmailToolSessionId = activation.nextRaw()["toolSessionId"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-email", """{"code":"$code"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")
    }

    /** Runs enroll-email through to Completed on the given channel, returns the confirmed email. */
    private fun enrollEmail(channelSessionId: String): String {
        val email = "max.mustermann+${UUID.randomUUID()}@example.com"
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"email":"$email"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"code":"$code"}""")
        return email
    }

    @Test
    fun mfaCombination_smsAndPasswordTogetherReachLoa2_whileEachAloneIsOnlyLoa1() {
        // Channel requires loa2 up front, so registration can't stop after a single loa1-rated
        // factor - it must chain further, differently-typed ones too.
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )

        // First factor (sms): alone it's loa1, not the required loa2, so registration continues.
        val enrollSmsToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (smsTan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
        // Only enroll-email is left as a candidate (sms is already active, password still needs a
        // confirmed email first) - single-candidate skip goes straight to it.
        assertThat(afterSms.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))

        // Second factor (email): sms+email are BOTH possession, so this alone still doesn't
        // reach loa2 - but it unlocks enroll-password (requiresConfirmedEmail).
        enrollEmail(channelSessionId)

        // Third factor (password, a KNOWLEDGE factor): together with sms/email this combines to loa2.
        val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        val enrolled = patch(
            "/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password",
            """{"password":"correct-horse-battery"}"""
        )
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(finalChannel.channel()["currentAcr"]).isEqualTo("loa2")
        @Suppress("UNCHECKED_CAST")
        assertThat(finalChannel.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "sms", "email", "password")

        // --- Fresh app session on the same device (no re-identification, so fsc's own loa2 isn't in play this time) ---
        val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
        // No single method alone reaches loa2, so the login offers a pick among all three.
        assertThat(loginStart.next()).isEqualTo(mapOf("type" to "flow", "context" to "auth", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email", "auth-password")

        val (loginTan, smsActivation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
        }
        val authSmsToolSessionId = smsActivation.nextRaw()["toolSessionId"] as String
        val afterSmsAuth = patch("/orchestrator/api/v1/tools/$authSmsToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
        // sms alone is only loa1, and email would be the SAME factor type (no MFA progress) - only
        // password is offered next.
        assertThat(afterSmsAuth.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth"))

        // Same rule as above: activate auth-password explicitly, don't reuse afterSmsAuth's (auth-sms) session id.
        val authPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
        val authenticated = patch(
            "/orchestrator/api/v1/tools/$authPasswordToolSessionId/auth-password",
            """{"password":"correct-horse-battery"}"""
        )
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa2")
    }

    @Test
    fun deviceLink_isWrittenAssoonAsOneAuthMethodExists_notOnlyOnceTheChannelsOwnFloorIsReached() {
        // Channel requires loa2, so a single loa1-rated method isn't enough for THIS channel -
        // registration doesn't finish yet, it offers enroll-email next.
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        assertThat(afterSms.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))

        // Abandon here (never enroll email/password, never reach this channel's own loa2 floor) -
        // a fresh app session (new channel, plain default loa1 floor) must still recognize this
        // device via the sms method already on file, not fall back to ident-fsc.
        val newChannel = post("/orchestrator/api/v1/app/channels")
        assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))

        val (loginTan, activation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/${newChannel.channel()["channelSessionId"]}/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))
    }

    @Test
    fun deviceLink_isAlsoWrittenWhenReIdentifyingIntoAnAlreadyEnrolledAccount_notOnlyOnFirstEnrollment() {
        // Register+enroll fully on binding key #1 (writes the DeviceAccountLink for key #1).
        registerAndAuthenticate()

        // Simulate a device whose key isn't linked yet (e.g. a fresh browser profile, or the
        // original key was lost) - "auto" correctly falls back to ident-fsc since key #2 has no
        // link yet.
        currentBindingKeyRef = "binding-" + UUID.randomUUID()
        val reidentified = post("/orchestrator/api/v1/app/channels")
        assertThat(reidentified.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        val channelSessionId = reidentified.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
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
            post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")

        // A third, brand-new channel on the SAME key #2 must now skip straight to LOGIN too.
        val thirdChannel = post("/orchestrator/api/v1/app/channels")
        assertThat(thirdChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))
    }

    @Test
    fun logout_endsTheChannelForGood_aNewOneStartsAFreshLoginViaTheDeviceLink() {
        val channelSessionId = registerAndAuthenticate()
        val beforeLogout = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(beforeLogout.channel()["state"]).isEqualTo("AUTHENTICATED")

        // Unlike Cancel (which leaves an AUTHENTICATED channel untouched, nothing to cancel),
        // Logout always ends the channel.
        assertThat(deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")).isEqualTo(HttpStatus.NO_CONTENT)

        // The old channelSessionId is dead - GET still resolves it (same key, valid binding),
        // but it stays LOGGED_OUT and reports no next step; it is never silently re-derived.
        val loggedOutChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(loggedOutChannel.channel()["state"]).isEqualTo("LOGGED_OUT")
        assertThat(loggedOutChannel["next"]).isNull()
        assertThat(loggedOutChannel.channel()["currentAcr"]).isNull()

        // A brand-new channel on the SAME device (same DPoP key) still recognizes the account via
        // DeviceAccountLink and skips straight to LOGIN instead of a fresh ident-fsc.
        val newChannel = post("/orchestrator/api/v1/app/channels")
        val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
        assertThat(newChannelSessionId).isNotEqualTo(channelSessionId)
        assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))

        val (tan, activation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")
    }

    @Test
    fun logout_duringActiveRegistration_cancelsTheProcessToo() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )

        assertThat(deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")).isEqualTo(HttpStatus.NO_CONTENT)

        // The old ident-fsc tool session is no longer part of any active process.
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)

        // Half-registered isn't a returning user (same rule as plain Cancel, docs/06-ablaeufe.md
        // via ProcessCancellationService): no account was ever fully provisioned, so the new
        // channel starts registration again, not LOGIN.
        val newChannel = post("/orchestrator/api/v1/app/channels")
        assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
    }

    @Test
    fun logout_withMismatchedBindingKey_isForbidden() {
        val channelSessionId = registerAndAuthenticate()

        currentBindingKeyRef = "a-completely-different-binding-key"

        val exception = assertThrows<HttpClientErrorException> {
            deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun weakPassword_isRejectedAsBadRequest() {
        // loa2 up front: default loa1 would already be satisfied by email alone, ending
        // registration (finishAsAuthenticated -> process consumed) before enroll-password could
        // ever be activated.
        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        enrollEmail(channelSessionId)
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String

        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password", """{"password":"short"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun enrollPassword_withoutConfirmedEmail_isRejectedAsConflict() {
        // Bypass attempt: activate enroll-password directly while still in an "enrollment"
        // selection context, without ever having confirmed an email - validateActivation alone
        // only checks the CATEGORY (ENROLL) matches, not that this specific toolId was actually
        // offered, so ToolControllerSupport must enforce the requiresConfirmedEmail precondition
        // itself, not just rely on it being excluded from stepData.options.
        val channelSessionId = identify()

        val exception = assertThrows<HttpClientErrorException> {
            post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun accountLevelThrottle_locksAfterRepeatedFailedAuthAttempts_acrossFreshToolSessions() {
        registerAndAuthenticate()
        // Fresh app session on the same device - each iteration below mints its OWN ToolSession
        // (a fresh per-session retryCount every time), proving the account-level throttle catches
        // what the per-session retry counter alone cannot.
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

        repeat(5) {
            val activation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
            val toolSessionId = activation.nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"000000"}""")
        }

        val exception = assertThrows<HttpClientErrorException> {
            post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
        }
        assertThat(exception.statusCode.value()).isEqualTo(423)
    }

    @Test
    fun accountLevelThrottle_resetsOnSuccessfulAuth() {
        registerAndAuthenticate()
        val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

        // A few failures, but not enough to lock - then a genuine success should clear the counter.
        repeat(3) {
            val activation = post("/orchestrator/api/v1/app/channels/$freshChannelSessionId/tools/auth-sms")
            val toolSessionId = activation.nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"000000"}""")
        }
        val (tan, activation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$freshChannelSessionId/tools/auth-sms")
        }
        val toolSessionId = activation.nextRaw()["toolSessionId"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"$tan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        // Confirm the counter was actually reset, not just "not yet locked": two more fresh
        // failures on ANOTHER new session right after a success should NOT be treated as
        // already at 3/5 - they still land on the same account via the device link.
        val nextChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        repeat(2) {
            val retryActivation = post("/orchestrator/api/v1/app/channels/$nextChannelSessionId/tools/auth-sms")
            val retryToolSessionId = retryActivation.nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$retryToolSessionId/auth-sms", """{"tan":"000000"}""")
        }
        // Still allowed - only 2 failures since the reset, well under the lock threshold.
        val stillAllowed = post("/orchestrator/api/v1/app/channels/$nextChannelSessionId/tools/auth-sms")
        assertThat(stillAllowed.nextRaw()["toolSessionId"]).isNotNull()
    }

    @Test
    fun getMethods_readsTheSameActiveMethodsAsTheChannelResponse() {
        // No account known yet - empty collection, not an error (docs/05-api.md #2).
        val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        @Suppress("UNCHECKED_CAST")
        assertThat(get("/orchestrator/api/v1/app/channels/$freshChannelSessionId/methods")["methods"] as List<String>).isEmpty()

        val channelSessionId = registerAndAuthenticate()
        @Suppress("UNCHECKED_CAST")
        val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<String>
        assertThat(methods).containsExactlyInAnyOrder("sms")

        val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["activeMethods"] as List<String>).isEqualTo(methods)
    }

    @Test
    fun manageMethods_addsAnotherMethodOnAnAuthenticatedChannel() {
        val channelSessionId = registerAndAuthenticate()

        val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
        // sms already active; email is offered (password still needs a confirmed email first).
        assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))

        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        val email = "manage-methods-${UUID.randomUUID()}@example.com"
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"email":"$email"}""")
        }
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"code":"$code"}""")
        // Finishes immediately after ONE enrollment, regardless of whether some higher floor was
        // reached - unlike REGISTRATION, MANAGE_METHODS never depends on canAccountReach.
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["currentAmr"] as List<String>).contains("email")
    }

    @Test
    fun manageMethods_withNoRemainingCandidates_reportsNothingToAddInsteadOfErroring() {
        val channelSessionId = registerAndAuthenticate()
        post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
        enrollEmail(channelSessionId)

        post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
        val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

        // sms, email and password are now all active - nothing left in the catalog to offer.
        val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
        assertThat(started["stepData"]).isEqualTo(mapOf("message" to "Keine weiteren Mittel verfuegbar"))
        assertThat(started.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))
    }

    @Test
    fun deactivateMethod_rejectsWhenItWouldDropBelowTheChannelsFloor() {
        val channelSessionId = registerAndAuthenticate()

        val exception = assertThrows<HttpClientErrorException> {
            delete("/orchestrator/api/v1/app/channels/$channelSessionId/methods/sms")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun deactivateMethod_succeedsWhenAnotherActiveMethodStillCoversTheFloor() {
        val channelSessionId = registerAndAuthenticate()
        post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
        enrollEmail(channelSessionId)

        delete("/orchestrator/api/v1/app/channels/$channelSessionId/methods/sms")

        // sms is a candidate again now that it was deactivated - email is already confirmed, so
        // password is ALSO now a valid candidate, hence a selection page rather than a skip.
        val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
        assertThat(started.next()).isEqualTo(mapOf("type" to "flow", "context" to "enrollment", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(started.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-password")
    }

    @Test
    fun manageMethods_stepsUpToLoa2_whenSessionOnlyHasLoa1Evidence() {
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
        // MANAGE_METHODS must offer ident-fsc as a way to reach loa2 instead of erroring out.
        val started = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
        assertThat(started.channel()["state"]).isEqualTo("STEP_UP_IN_PROGRESS")
        assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        val reIdentified = patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        // Re-identification alone already reaches loa2 - MANAGE_METHODS resumes immediately,
        // finishing back to AUTHENTICATED rather than demanding a further factor.
        assertThat(reIdentified.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterStepUp = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterStepUp.channel()["state"]).isEqualTo("AUTHENTICATED")
        assertThat(afterStepUp.channel()["currentAcr"]).isEqualTo("loa2")

        // Calling methods again now succeeds directly (loa2 already satisfied this session).
        val retried = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
        assertThat(retried.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))
    }

    @Test
    fun manageMethodsStepUp_reIdentifyingAsADifferentPerson_isRejected() {
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

    /** Registers via ident-fsc -> enroll-email -> enroll-password, returns the confirmed email. */
    private fun registerWithEmailAndPassword(password: String = "correct-horse-battery"): String {
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val email = enrollEmail(channelSessionId)
        val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"$password"}""")
        return email
    }

    @Test
    fun lookupLogin_viaPassword_authenticatesIntoExistingAccountAndRelinksTheDevice() {
        val email = registerWithEmailAndPassword()

        // Same mocked device, but intent=login forces lookup-based login regardless of the
        // DeviceAccountLink this device already has from registerWithEmailAndPassword above
        // (docs/04-orchestrierung.md, lookup-based login).
        val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
        val channelSessionId = loginStart.channel()["channelSessionId"] as String
        assertThat(loginStart.next()).isEqualTo(mapOf("type" to "flow", "context" to "auth", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")

        val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
        val authenticated = patch(
            "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
            """{"email":"$email","password":"correct-horse-battery"}"""
        )
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["currentAmr"] as List<String>).contains("password")

        // DeviceAccountLink re-written by the lookup login (idempotent here, same account) -
        // a subsequent plain intent=auto channel on this device goes straight to LOGIN again,
        // never REGISTRATION.
        val nextAuto = post("/orchestrator/api/v1/app/channels")
        assertThat(nextAuto.channel()["state"]).isNotEqualTo("REGISTERING")
    }

    @Test
    fun lookupLogin_viaSms_authenticatesIntoExistingAccount() {
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (enrollTan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
        val email = enrollEmail(channelSessionId)

        val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
        val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
        val lookupToolSessionId = post("/orchestrator/api/v1/app/channels/$lookupChannelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String

        val (loginTan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"email":"$email"}""")
        }
        val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"tan":"$loginTan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val channel = get("/orchestrator/api/v1/app/channels/$lookupChannelSessionId")
        assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
    }

    /**
     * activeMethods (ChannelResponse) is the account's full standing method list, distinct from
     * currentAmr (session evidence, docs/10-frontend.md). A device-bound LOGIN that only needs
     * ONE active method to satisfy the default loa1 floor never re-proves the account's other
     * methods - activeMethods must still report them, so the UI can offer to manage (e.g.
     * deactivate) a method the current session never touched.
     */
    @Test
    fun channelResponse_activeMethods_includesMethodsNotProvenThisSession() {
        // loa2 up front: default loa1 would already be satisfied by email alone, ending
        // registration (finishAsAuthenticated -> process consumed) before enroll-password could
        // ever be activated (same reasoning as passwordEnrollmentAndSubsequentLoginFlow above).
        val channelSessionId =
            post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        enrollEmail(channelSessionId)
        val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

        deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")

        // Fresh device-bound LOGIN (same device, DeviceAccountLink still points here): default
        // loa1 floor is satisfied by a single method, so this proves ONLY password.
        val loginStart = post("/orchestrator/api/v1/app/channels")
        val loginChannelSessionId = loginStart.channel()["channelSessionId"] as String
        val authToolSessionId = post("/orchestrator/api/v1/app/channels/$loginChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val channel = get("/orchestrator/api/v1/app/channels/$loginChannelSessionId")
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["currentAmr"] as List<String>).containsExactly("password")
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["activeMethods"] as List<String>).containsExactlyInAnyOrder("email", "password")
    }

    @Test
    fun lookupLogin_viaEmail_authenticatesIntoExistingAccount() {
        val email = registerWithEmailAndPassword()

        val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
        val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
        val lookupToolSessionId = post("/orchestrator/api/v1/app/channels/$lookupChannelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String

        val (loginCode, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"email":"$email"}""")
        }
        val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"code":"$loginCode"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val channel = get("/orchestrator/api/v1/app/channels/$lookupChannelSessionId")
        assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["currentAmr"] as List<String>).contains("email")
    }

    @Test
    fun lookupLogin_withUnknownEmail_failsIndistinguishablyFromAWrongCredential() {
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String

        // Same shape as a wrong password against a real account (200, Failed -> retry offered)
        // - never a distinct HTTP error for "unknown email" (enumeration protection).
        val response = patch(
            "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
            """{"email":"nobody@example.com","password":"whatever12"}"""
        )
        assertThat(response.stepData()["error"]).isNotNull()
        assertThat(response.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-password-lookup", "step" to "auth"))
    }

    @Test
    fun lookupLoginIntent_onNeverLinkedDevice_offersLookupToolsNotRegistration() {
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
        @Suppress("UNCHECKED_CAST")
        assertThat(channelResponse.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")
    }

    @Test
    fun registerIntent_onAlreadyLinkedDevice_startsFreshRegistrationInstead() {
        registerAndAuthenticate()

        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")
        assertThat(channelResponse.channel()["state"]).isEqualTo("REGISTERING")
        assertThat(channelResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val MAP_TYPE = Map::class.java as Class<Map<String, Any?>>
    }
}

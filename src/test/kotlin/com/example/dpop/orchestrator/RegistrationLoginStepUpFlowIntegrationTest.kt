package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.session.ChannelSessionRepository
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
 * Cancel and Back/Switch flows end to end against the real HTTP layer, matching docs/05-api.md
 * and docs/06-ablaeufe.md. DPoP validation is mocked (the crypto itself is covered separately);
 * everything downstream - orchestration, policy, persistence - is real.
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
    private lateinit var channelSessionRepository: ChannelSessionRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // The JDK's default request factory can't send PATCH; HttpClient5 (already a test dep) can.
    private val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    @BeforeEach
    fun resetDatabase() {
        // Children first (FK order); person/fsc_code seed data is left untouched.
        listOf(
            "id_fsc_tool_data", "enroll_sms_tool_data", "auth_sms_use_tool_data",
            "tool_session", "process_session", "session_event",
            "channel_session", "auth_context", "account", "auth_sms"
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

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.next(): Map<String, Any?> = this["next"] as Map<String, Any?>

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
        val tan = Regex("""TAN (\d{6}) an""").find(printed)?.groupValues?.get(1)
            ?: error("No mock TAN found in captured output: $printed")
        return tan to response
    }

    /** Runs ident-fsc through to Identified using the standard test person, returns the channelSessionId. */
    private fun identify(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels")["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/ident-fsc")["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        return channelSessionId
    }

    /** Runs ident-fsc + enroll-sms through to AUTHENTICATED, returns the channelSessionId. */
    private fun registerAndAuthenticate(): String {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/enroll-sms")["toolSessionId"] as String
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
        val channelSessionId = channelResponse["channelSessionId"] as String
        assertThat(channelResponse["state"]).isEqualTo("REGISTERING")
        assertThat(channelResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        // 2) Activate ident-fsc
        val identActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/ident-fsc")
        val identToolSessionId = identActivation["toolSessionId"] as String
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

        // 4) Supply the valid FSC -> identified, single enroll candidate skips straight to enroll-sms
        val identified = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
        assertThat(identified.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "enroll"))

        // 5) Activate enroll-sms
        val enrollActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/enroll-sms")
        val enrollToolSessionId = enrollActivation["toolSessionId"] as String
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

        // 7) Confirm TAN -> enrolled, account now reaches loa2 with one factor -> authenticated
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        // 8) Channel now reports AUTHENTICATED with fsc+sms evidence
        val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(finalChannel["state"]).isEqualTo("AUTHENTICATED")
        assertThat(finalChannel["currentAcr"]).isEqualTo("loa2")
        @Suppress("UNCHECKED_CAST")
        assertThat(finalChannel["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "sms")

        // --- Simulate a fresh login: same device/channel, but its AuthContext evidence is gone
        // (e.g. app restart after the server-side context expired) ---
        val channelUuid = UUID.fromString(channelSessionId)
        val stored = channelSessionRepository.findById(channelUuid).orElseThrow()
        stored.authContextId = null
        channelSessionRepository.save(stored)

        val loginStart = post("/orchestrator/api/v1/app/channels")
        assertThat(loginStart["channelSessionId"]).isEqualTo(channelSessionId)
        assertThat(loginStart.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))

        val (authTan, authActivation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/auth-sms")
        }
        val authToolSessionId = authActivation["toolSessionId"] as String

        val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterLogin = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(afterLogin["state"]).isEqualTo("AUTHENTICATED")
    }

    @Test
    fun invalidPhoneNumber_isRejectedAsBadRequest() {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/enroll-sms")["toolSessionId"] as String

        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"not-a-number"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun exhaustedRetries_endTheProcessAsGone() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels")["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/ident-fsc")["toolSessionId"] as String
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
        val channelSessionId = post("/orchestrator/api/v1/app/channels")["channelSessionId"] as String

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
            patch("/orchestrator/api/v1/app/channels/$channelSessionId", """{"requiredAcr":"loa3"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.GONE)
    }

    @Test
    fun cancelDuringRegistration_resetsAndOffersAFreshStart() {
        val channelSessionId = post("/orchestrator/api/v1/app/channels")["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/ident-fsc")["toolSessionId"] as String
        // Get all the way to Identified (account created) before cancelling, to prove the
        // channel doesn't stay half-bound to that account afterwards.
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )

        val cancelled = post("/orchestrator/api/v1/app/channels/$channelSessionId/cancel")
        // ChannelState diagram (docs/02-domaenenmodell.md #3): REGISTERING -> ANONYMOUS -> a
        // fresh registration is offered immediately, so the response already shows REGISTERING
        // again; single-candidate skip goes straight to the tool (same as the initial channel init).
        assertThat(cancelled["state"]).isEqualTo("REGISTERING")
        assertThat(cancelled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        // The old ident-fsc tool session is no longer part of any active process.
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun cancelDuringLogin_offersAFreshLoginAttempt() {
        val channelSessionId = registerAndAuthenticate()

        // Simulate a fresh login (same device, lost server-side evidence) and activate auth-sms.
        val channelUuid = UUID.fromString(channelSessionId)
        val stored = channelSessionRepository.findById(channelUuid).orElseThrow()
        stored.authContextId = null
        channelSessionRepository.save(stored)
        post("/orchestrator/api/v1/app/channels")
        post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/auth-sms")

        val cancelled = post("/orchestrator/api/v1/app/channels/$channelSessionId/cancel")
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
        val channelSessionId = post("/orchestrator/api/v1/app/channels")["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/ident-fsc")["toolSessionId"] as String
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
        val channelSessionId = registerAndAuthenticate()

        // Simulate a fresh login and activate auth-sms TWICE (e.g. a double client request) -
        // each activation mints its own ToolSession with its own issued TAN.
        val channelUuid = UUID.fromString(channelSessionId)
        val stored = channelSessionRepository.findById(channelUuid).orElseThrow()
        stored.authContextId = null
        channelSessionRepository.save(stored)
        post("/orchestrator/api/v1/app/channels")

        val firstActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/auth-sms")
        val firstToolSessionId = firstActivation["toolSessionId"] as String
        val secondActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/auth-sms")
        val secondToolSessionId = secondActivation["toolSessionId"] as String
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
        val channelSessionId = post("/orchestrator/api/v1/app/channels")["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/ident-fsc")["toolSessionId"] as String

        // No prior step to go "back" to for the first (IDENT) tool - equivalent to Cancel.
        // Single-candidate skip goes straight back to the tool (same as the initial channel init).
        val result = delete("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc")
        assertThat(result.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

        val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(channel["state"]).isEqualTo("REGISTERING")
    }

    @Test
    fun switchAwayFromEnrollTool_reoffersEnrollmentCandidates() {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/enroll-sms")["toolSessionId"] as String

        // Only one enrollment method exists in the catalog today, so switching away just
        // re-offers that same one - but the OLD tool session is abandoned either way.
        val result = delete("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms")
        assertThat(result.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "enroll"))

        // The abandoned tool session is gone even though the re-offered toolId is the same one.
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        // Re-activating works fine and mints a new tool session.
        val reactivated = post("/orchestrator/api/v1/app/channels/$channelSessionId/tool-activate/enroll-sms")
        assertThat(reactivated["toolSessionId"]).isNotEqualTo(enrollToolSessionId)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val MAP_TYPE = Map::class.java as Class<Map<String, Any?>>
    }
}

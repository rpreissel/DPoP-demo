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
 * The properties that only exist because the model is a per-intent state machine
 * (docs/04-orchestrierung.md): the FAST fallback ladder and its memory of what was declined,
 * identification as a LOGIN path when nothing else is left, the states LOGIN_LOOKUP structurally
 * does not have, and the journey-wide attempt budget.
 *
 * Deliberately separate from RegistrationLoginStepUpFlowIntegrationTest, which covers the happy
 * paths of each intent end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JourneyLadderIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var dpopValidator: DpopValidator

    @MockitoBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    @BeforeEach
    fun resetDatabase() {
        listOf(
            "id_fsc_tool_data", "enroll_sms_tool_data", "auth_sms_use_tool_data",
            "enroll_password_tool_data", "auth_password_use_tool_data",
            "enroll_email_tool_data", "auth_email_use_tool_data",
            "auth_sms_lookup_tool_data", "auth_password_lookup_tool_data",
            "tool_session", "auth_journey", "session_event",
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

    // Ladder ------------------------------------------------------------------

    @Test
    fun fastLadder_decliningTheAuthStage_fallsThroughToIdentification() {
        registerWithSms()

        // Fresh session on the same device: the link routes straight to the existing sms method.
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val next = get("/orchestrator/api/v1/app/channels/$channelSessionId").next()
        assertThat(next).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))

        // Backing out of the only remaining auth method is not a dead end: the ladder falls
        // through to its last stage, identification - which is exactly what the old model could
        // not express, because an empty candidate list aborted the whole process.
        val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
        val afterDecline = delete("/orchestrator/api/v1/tools/$toolSessionId/auth-sms")
        assertThat(afterDecline.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
    }

    @Test
    fun fastLadder_identifyingAfterDecliningAuth_logsIntoTheSameAccountWithoutRegisteringAgain() {
        val accountId = registerWithSms()

        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val authToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
        delete("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms")

        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        val identified = patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )

        // The same KVNR finds the SAME account again - "registration" versus "login" was never a
        // choice made up front, only an observation about which path was taken. So identifying on
        // the last stage must not leave a second account behind.
        assertThat(identified.next()["type"]).isNotNull()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT MIN(id) FROM account", Long::class.java)).isEqualTo(accountId)
    }

    // LOGIN_LOOKUP -------------------------------------------------------------

    @Test
    fun lookupLogin_cannotBeTalkedIntoAnIdentification() {
        registerWithSms()

        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            .channel()["channelSessionId"] as String

        // No state of this intent ever offers an identification, so naming the tool directly is
        // rejected at the boundary rather than blowing up deeper in with a 500.
        val exception = assertThrows<HttpClientErrorException> {
            post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    // Attempt budget -----------------------------------------------------------

    @Test
    fun attemptBudget_spansTheWholeJourney_notASingleTool() {
        registerWithSms()
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

        // Two failures on one tool session, the third on a FRESH one: under a per-tool counter
        // the fresh session would start over at zero and the journey would survive indefinitely.
        val firstToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"000000"}""")
        patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"000000"}""")

        val secondToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
        val exception = assertThrows<HttpClientErrorException> {
            patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"000000"}""")
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.GONE)
    }

    // Entry intent -------------------------------------------------------------

    @Test
    fun cancellingALookupLogin_restartsALookupLogin_notARegistration() {
        registerWithSms()
        currentBindingKeyRef = "binding-" + UUID.randomUUID()

        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            .channel()["channelSessionId"] as String
        val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String

        // The channel remembers WHICH intent it was entered with, so abandoning does not silently
        // turn a "log me into my existing account" into "let's register you".
        delete("/orchestrator/api/v1/tools/$toolSessionId/auth-sms-lookup")
        val afterCancel = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
        assertThat(afterCancel.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(afterCancel.stepData()["options"] as List<String>)
            .containsExactlyInAnyOrder("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")
    }

    // Helpers ------------------------------------------------------------------

    /** Registers the seeded test person with sms only and returns the resulting accountId. */
    private fun registerWithSms(): Long {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM account", Long::class.java)!!
    }

    private fun headers(): HttpHeaders = HttpHeaders().apply {
        set("DPoP", "mock-dpop-token")
        set("Content-Type", "application/json")
    }

    private fun post(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, headers()), MAP_TYPE)
            .let { assertThat(it.statusCode.is2xxSuccessful).isTrue(); it.body!! }

    private fun patch(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), MAP_TYPE)
            .let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    private fun get(url: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.GET, HttpEntity<Void>(headers()), MAP_TYPE)
            .let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    private fun delete(url: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), MAP_TYPE)
            .let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.channel(): Map<String, Any?> = this["channel"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nextRaw(): Map<String, Any?> = this["next"] as Map<String, Any?>

    private fun Map<String, Any?>.next(): Map<String, Any?> = nextRaw().filterKeys { it != "toolSessionId" }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.stepData(): Map<String, Any?> = this["stepData"] as Map<String, Any?>

    /** The mock SMS/email gateway prints the code to stdout; capture it the same way the flow test does. */
    private fun <T> captureMockTan(block: () -> T): Pair<String, T> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        val result = try {
            block()
        } finally {
            System.setOut(original)
        }
        val tan = Regex("""\b(\d{6})\b""").find(buffer.toString())?.groupValues?.get(1)
            ?: error("No TAN found in captured output: $buffer")
        return tan to result
    }

    companion object {
        private val MAP_TYPE = object : org.springframework.core.ParameterizedTypeReference<Map<String, Any?>>() {}
    }
}

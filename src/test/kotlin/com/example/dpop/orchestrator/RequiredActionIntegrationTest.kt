package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.nimbusds.jose.jwk.JWK
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID

/**
 * Registration's Required Actions (docs/04-orchestrierung.md #2, Keycloak's "Required Action"
 * concept): a confirmed email must be in place before REGISTRATION can finish, independent of
 * whether the channel's requiredAcr is already satisfied by other means. Kept as its own small
 * file rather than growing the already-large RegistrationLoginStepUpFlowIntegrationTest.kt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RequiredActionIntegrationTest {

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
            "enroll_email_tool_data", "auth_email_use_tool_data",
            "tool_session", "process_session", "session_event",
            "channel_session", "auth_context", "account", "auth_sms",
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
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, headers()), MAP_TYPE)
            .let { assertThat(it.statusCode.is2xxSuccessful).isTrue(); it.body!! }

    private fun patch(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), MAP_TYPE)
            .let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    private fun get(url: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.GET, HttpEntity<Void>(headers()), MAP_TYPE)
            .let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.next(): Map<String, Any?> = (this["next"] as Map<String, Any?>).minus("toolSessionId")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nextRaw(): Map<String, Any?> = this["next"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.channel(): Map<String, Any?> = this["channel"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.stepData(): Map<String, Any?> = this["stepData"] as Map<String, Any?>

    /** activeMethods entries are {id, method, label} objects - pulls just the method names. */
    @Suppress("UNCHECKED_CAST")
    private fun List<*>.methodNames(): List<String> = (this as List<Map<String, Any?>>).map { it["method"] as String }

    private fun captureMockTan(block: () -> Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val response = try {
            block()
        } finally {
            System.setOut(original)
        }
        val tan = Regex("""(?:TAN|Code) (\d{6}) an""").find(buffer.toString())?.groupValues?.get(1)
            ?: error("No mock TAN/code found in captured output: $buffer")
        return tan to response
    }

    private fun identify(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        return channelSessionId
    }

    private fun enrollEmail(channelSessionId: String): String {
        val email = "required-action-${UUID.randomUUID()}@example.com"
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"email":"$email"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"code":"$code"}""")
        return email
    }

    @Test
    fun registration_reachingTheAcrFloorViaSmsAlone_stillMustEnrollEmailBeforeFinishing() {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }

        // sms alone already reaches the default loa1 floor - without the Required Action, this
        // would go straight to AUTHENTICATED.
        val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        assertThat(afterSms.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))

        val channelMidway = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(channelMidway.channel()["state"]).isEqualTo("REGISTERING")

        enrollEmail(channelSessionId)

        val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(finalChannel.channel()["state"]).isEqualTo("AUTHENTICATED")
        @Suppress("UNCHECKED_CAST")
        assertThat((finalChannel.channel()["activeMethods"] as List<*>).methodNames()).containsExactlyInAnyOrder("sms", "email")
    }

    @Test
    fun registration_choosingEmailFirst_alreadySatisfiesBothRequiredActionsInOneStep() {
        val channelSessionId = identify()

        // Enroll email FIRST (its own maxAcr already reaches the default loa1 floor alone) -
        // both Required Actions (confirmed email, sufficient login method) are satisfied by this
        // single enrollment, so registration finishes immediately - no second forced sms step,
        // proving the order of enrollment doesn't matter, only that both end up satisfied.
        val enrollEmailToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        val email = "required-action-order-${UUID.randomUUID()}@example.com"
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollEmailToolSessionId/enroll-email", """{"email":"$email"}""")
        }
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollEmailToolSessionId/enroll-email", """{"code":"$code"}""")
        assertThat(enrolled.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        @Suppress("UNCHECKED_CAST")
        assertThat((finalChannel.channel()["activeMethods"] as List<*>).methodNames()).containsExactlyInAnyOrder("email")
    }

    @Test
    fun existingAccountWithoutConfirmedEmail_canStillLoginAndAddAMethodViaManageMethods() {
        // Registration WITHOUT the Required Action gate (simulates an account provisioned before
        // this feature existed, or any other pre-existing state) - directly seed via device-bound
        // enrollment only, skip enroll-email entirely by never activating it.
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        // Registration is now stuck offering enroll-email (by design) - directly flip the account
        // to AUTHENTICATED without it, via SQL, to reproduce a pre-existing account that predates
        // this Required Action (the scope this test guards: LOGIN/MANAGE_METHODS must never
        // retroactively enforce it).
        jdbcTemplate.update("UPDATE channel_session SET state = 'AUTHENTICATED' WHERE channel_session_id = ?", channelSessionId)
        jdbcTemplate.update("UPDATE process_session SET state = 'CONSUMED' WHERE channel_session_id = ?", channelSessionId)

        // A fresh channel on the same device recognizes the account via DeviceAccountLink and logs
        // in via the existing sms method - no email confirmation demanded.
        val newChannel = post("/orchestrator/api/v1/app/channels")
        assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))
        val newChannelSessionId = newChannel.channel()["channelSessionId"] as String

        val (loginTan, activation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        // MANAGE_METHODS itself always demands loa2 session evidence first (unrelated to Required
        // Actions - ChannelService.MANAGE_METHODS_REQUIRED_ACR); this session only proved sms
        // (loa1), so it forces a step-up via re-identification first.
        val started = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
        assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        val stepUpIdentToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        val reIdentified = patch(
            "/orchestrator/api/v1/tools/$stepUpIdentToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        assertThat(reIdentified.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        // NOW retry - loa2 is satisfied this session, so MANAGE_METHODS offers enroll-email as a
        // normal (not forced) candidate alongside enroll-device - a selection page, not an
        // automatic skip straight into enroll-email. enroll-password is correctly excluded (still
        // requires a confirmed email first, which this account deliberately doesn't have) - proves
        // the Required Action's absence here doesn't quietly waive OTHER, unrelated preconditions.
        val retried = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
        assertThat(retried.next()).isEqualTo(mapOf("type" to "flow", "context" to "enrollment", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        assertThat(retried.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-email", "enroll-device")
    }

    companion object {
        private val MAP_TYPE = object : ParameterizedTypeReference<Map<String, Any?>>() {}
    }
}

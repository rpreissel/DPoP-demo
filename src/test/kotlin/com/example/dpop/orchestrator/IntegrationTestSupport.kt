package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.ninjasquad.springmockk.MockkBean
import com.nimbusds.jose.jwk.JWK
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.context.annotation.Import
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID

/**
 * Shared HTTP-client/DB-reset plumbing for every orchestrator integration test. The concrete
 * suites used to independently duplicate this near-verbatim: RestTemplate setup, resetDatabase's
 * table list, headers(), post/patch/get/delete, and the response-envelope Map extension helpers.
 *
 * Mock wiring for what DpopValidator's stub actually returns differs per suite (most stub a fake
 * JWK; DeviceBindingIntegrationTest/MultiDeviceCredentialIntegrationTest need the real
 * JwkThumbprintService, so they supply a real EC key instead) and stays local to each subclass's
 * own `beforeEach`. The default stub configured here (fake JWK, fresh bindingKeyRef per call) is
 * what the flow helpers below ([identify], [registerAndAuthenticate], ...) are written against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PinnedToolCatalogTestConfig::class)
abstract class IntegrationTestSupport : BehaviorSpec() {

    @LocalServerPort
    protected var port: Int = 0

    @MockkBean
    protected lateinit var dpopValidator: DpopValidator

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var toolRegistry: ToolHandlerRegistry

    // The JDK's default request factory can't send PATCH; HttpClient5 (already a test dep) can.
    protected val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    protected val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    protected var currentBindingKeyRef: String = ""

    init {
        beforeEach {
            // Children first (FK order); person/fsc_code seed data is left untouched. The union
            // of every table any suite ever touches - deleting from one a given test never
            // populated is a harmless no-op.
            listOf(
                "id_fsc_tool_data", "enroll_sms_tool_data", "auth_sms_use_tool_data",
                "auth_sms_lookup_tool_data", "enroll_password_tool_data", "auth_password_use_tool_data",
                "auth_password_lookup_tool_data", "enroll_email_tool_data", "auth_email_use_tool_data",
                "auth_device_tool_data", "enroll_device_tool_data", "device_enrollment",
                "tool_session", "auth_journey", "session_event", "journey_log",
                "channel_session_available_tools", "channel_session", "auth_context", "account", "auth_sms", "auth_password",
                "device_account_link", "attempt_throttle", "tool_availability", "dpop_proof_replay"
            ).forEach { jdbcTemplate.update("DELETE FROM $it") }
        }
    }

    /**
     * Stubs DpopValidator to return a fake JWK, and [jwkThumbprintService] to thumbprint that
     * exact fake JWK to a fresh [currentBindingKeyRef]. Call from a subclass's own `beforeEach`
     * once it has its own `@MockkBean jwkThumbprintService` field to pass in.
     *
     * Device-binding tests must NOT use this: they need real ECDSA key material, since a mocked
     * JwkThumbprintService would defeat the point of testing key-binding correctness. Those wire
     * DpopValidator directly against a real generated EC key instead.
     */
    protected fun stubDpopWithFakeJwk(jwkThumbprintService: JwkThumbprintService) {
        val fakeJwk = mockk<JWK>()
        every { dpopValidator.validate(any(), any(), any()) } returns DpopProof(
            token = "mock-token",
            publicKey = fakeJwk,
            jti = UUID.randomUUID().toString(),
            htm = "POST",
            htu = "http://localhost/mock",
            issuedAt = Instant.now(),
            nonce = null
        )
        currentBindingKeyRef = "binding-" + UUID.randomUUID()
        // Lazy on purpose: a test can reassign currentBindingKeyRef mid-run (e.g. to simulate a
        // mismatched binding key) and every subsequent call must see the NEW value.
        every { jwkThumbprintService.computeThumbprint(fakeJwk) } answers { currentBindingKeyRef }
    }

    protected fun headers(): HttpHeaders = HttpHeaders().apply {
        set("DPoP", "mock-dpop-token")
        set("Content-Type", "application/json")
    }

    protected fun post(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.POST, HttpEntity(withDefaultAvailableTools(url, body), headers()), mapType
        ).let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    /**
     * `availableTools` is a required field on `POST /channels` (docs/03-tool-architektur.md,
     * availability) - every one of the ~60 call sites across these suites would otherwise need it
     * spelled out by hand. Centralized here instead: unless a test already declares its own
     * `availableTools` (to test a restricted set), it gets the full catalog, i.e. "this client
     * supports everything" - the neutral default for flows not about availability itself.
     */
    private fun withDefaultAvailableTools(url: String, body: String): String {
        if (url != "/orchestrator/api/v1/app/channels" || body.contains("availableTools")) return body
        val allToolIds = toolRegistry.descriptors().joinToString(",", "[", "]") { "\"${it.toolId}\"" }
        return if (body.isBlank() || body.trim() == "{}") {
            """{"availableTools":$allToolIds}"""
        } else {
            body.trim().removeSuffix("}") + ""","availableTools":$allToolIds}"""
        }
    }

    protected fun patch(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    protected fun put(url: String, body: String): HttpStatus =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.PUT, HttpEntity(body, headers()), Void::class.java
        ).statusCode as HttpStatus

    protected fun get(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.GET, HttpEntity<Void>(headers()), mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    protected fun delete(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Logout returns 204 No Content (docs/05-api.md), no body to parse. */
    protected fun deleteNoContent(url: String): HttpStatus =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), Void::class.java
        ).statusCode as HttpStatus

    /**
     * `toolSessionId` (docs/05-api.md #2) is stripped here so the many exact-map assertions in
     * the concrete suites stay focused on routing (type/toolId|context/step) without each needing
     * to know the concrete session id; use [nextRaw] where the id itself is under test.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.next(): Map<String, Any?> = (this["next"] as Map<String, Any?>).minus("toolSessionId")

    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.nextRaw(): Map<String, Any?> = this["next"] as Map<String, Any?>

    /** The channel-level block every response carries now (docs/05-api.md #2: unified envelope). */
    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.channel(): Map<String, Any?> = this["channel"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.stepData(): Map<String, Any?> = this["stepData"] as Map<String, Any?>

    /** activeMethods/GET methods entries are {id, method, label} objects - pulls just the method names. */
    @Suppress("UNCHECKED_CAST")
    protected fun List<*>.methodNames(): List<String> = (this as List<Map<String, Any?>>).map { it["method"] as String }

    /** Mock SMS/email gateways only print the code to stdout (docs/05-api.md: never in the response). */
    protected fun captureMockTan(block: () -> Map<String, Any?>): Pair<String, Map<String, Any?>> {
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
    protected fun identify(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        reIdentifyViaFsc(channelSessionId)
        return channelSessionId
    }

    /**
     * Re-identifies via ident-fsc using the standard test person, on a channel that already
     * offers it (fresh channel, or mid step-up after [triggerEnrollmentStepUp]). Deliberately
     * doesn't assert what `next` looked like beforehand - callers that care about the exact
     * identification-candidate selection screen assert that themselves; this only drives the
     * fsc-specific mechanics, so it keeps working regardless of how many identification methods
     * the catalog offers.
     */
    protected fun reIdentifyViaFsc(channelSessionId: String): Map<String, Any?> {
        val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        return patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
    }

    /**
     * Triggers a step-up by requesting an enrollment while the channel's session evidence doesn't
     * reach the required floor yet - shared by the "re-identification is the only way to loa2"
     * scenarios. Only asserts the channel state, not the resulting `next`/`options` shape: how
     * many identification candidates get offered is a catalog detail, not this helper's job.
     */
    protected fun triggerEnrollmentStepUp(channelSessionId: String): Map<String, Any?> {
        val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
        started.channel()["state"] shouldBe "STEP_UP_IN_PROGRESS"
        return started
    }

    /** Runs enroll-email through to Completed on the given channel, returns the confirmed email. */
    protected fun enrollEmail(channelSessionId: String): String {
        val email = "max.mustermann+${UUID.randomUUID()}@example.com"
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"email":"$email"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"code":"$code"}""")
        return email
    }

    /**
     * Runs ident-fsc + enroll-sms + enroll-email through to AUTHENTICATED, returns the
     * channelSessionId. enroll-email is required even though sms alone already reaches the
     * default loa1 floor: a confirmed email is a Required Action of REGISTRATION
     * (docs/04-orchestrierung.md #2), not just an ACR-driven candidate.
     */
    protected fun registerAndAuthenticate(): String {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        enrollEmail(channelSessionId)
        return channelSessionId
    }

    /** Registers via ident-fsc -> enroll-email -> enroll-password, returns the confirmed email. */
    protected fun registerWithEmailAndPassword(password: String = "correct-horse-battery"): String {
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val email = enrollEmail(channelSessionId)
        val enrollPasswordToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"$password"}""")
        return email
    }
}

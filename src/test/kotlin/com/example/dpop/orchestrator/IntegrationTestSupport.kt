package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.admin.ADMIN_API
import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.support.AccountFixtures
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

    /** Seeds account preconditions through the domain services - see [AccountFixtures]. */
    @Autowired
    protected lateinit var accountFixtures: AccountFixtures

    // The JDK's default request factory can't send PATCH; HttpClient5 (already a test dep) can.
    protected val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    protected val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    protected var currentBindingKeyRef: String = ""

    init {
        beforeEach {
            // Children first (FK order); ext_stammdaten.person/freischaltcode seed data is left
            // untouched. The union of every table any suite ever touches - deleting from one a
            // given test never populated is a harmless no-op. account's own children cascade.
            listOf(
                "id_fsc.ident_tool_session", "id_eid.ident_tool_session",
                "auth_sms.enroll_tool_session", "auth_sms.auth_tool_session", "auth_sms.lookup_tool_session", "auth_sms.enrollment",
                "auth_password.enroll_tool_session", "auth_password.auth_tool_session", "auth_password.lookup_tool_session", "auth_password.enrollment",
                "auth_email.confirm_tool_session", "auth_email.enroll_tool_session", "auth_email.auth_tool_session", "auth_email.lookup_tool_session",
                "auth_device.enroll_tool_session", "auth_device.auth_tool_session", "auth_device.enrollment",
                "auth_kobil.enroll_tool_session", "auth_kobil.auth_tool_session", "auth_kobil.enrollment",
                // The foreign system's own rows. Wiped too, not because our retention covers them
                // (it does not - kobil_mock is not ours) but because a test must not inherit a
                // device binding from the previous one.
                "kobil_mock.ssms_assertion", "kobil_mock.ssms_user",
                "auth_qr.enroll_tool_session", "auth_qr.auth_tool_session", "auth_qr.lookup_tool_session", "auth_qr.confirm_tool_session",
                "auth_qr.login_request", "auth_qr.enrollment",
                "orchestrator.tool_session", "orchestrator.auth_journey", "orchestrator.session_event", "orchestrator.journey_log",
                "orchestrator.channel_session", "orchestrator.auth_context", "orchestrator.auth_evidence", "account.account",
                "orchestrator.device_account_link", "orchestrator.attempt_throttle", "orchestrator.tool_availability", "orchestrator.dpop_proof_replay",
                "orchestrator.feature_flag"
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
            "http://localhost:$port$url",
            HttpMethod.POST,
            HttpEntity(if (url == "/orchestrator/api/v1/app/channels") withDefaultAvailableTools(body) else body, headers()),
            mapType
        ).let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    /**
     * `availableTools` is a required field on channel creation (docs/03-tool-architektur.md,
     * availability) - every one of the ~60 call sites across these suites, App and kc-facade alike,
     * would otherwise need it spelled out by hand. Centralized here instead: unless a test already
     * declares its own `availableTools` (to test a restricted set), it gets the full catalog, i.e.
     * "this client supports everything" - the neutral default for flows not about availability
     * itself. `protected`, not `private`: [KcChannelIntegrationTest] builds its PATCH bodies
     * independently of [post] (a different HTTP method, its own headers) but needs the exact same
     * default.
     */
    protected fun withDefaultAvailableTools(body: String): String {
        if (body.contains("availableTools")) return body
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
            "http://localhost:$port$url", HttpMethod.PUT, HttpEntity(body, if (url.startsWith(ADMIN_API)) adminHeaders() else headers()), Void::class.java
        ).statusCode as HttpStatus

    /** Operator endpoints sit behind the admin login (AdminSecurityConfig) - the demo credentials from application.yml. */
    protected fun adminHeaders(): HttpHeaders = HttpHeaders().apply {
        setBasicAuth("admin", "admin")
        set("Content-Type", "application/json")
    }

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

    /**
     * Runs ident-fsc through to Identified using the standard test person, returns the
     * channelSessionId. Does NOT discharge the address obligation REGISTER now raises immediately
     * after the identification - use [identifyAndConfirmEmail] for the (far more common) case of a
     * test that only wants to get to the point where methods can be enrolled.
     *
     * [requiredAcr]/[intent]/[availableTools] are the channel-creation options tests vary; every
     * other caller gets the plain default channel. Spelling the whole "create a channel, activate
     * ident-fsc, PATCH the test person" sequence out by hand is what made a change to the
     * registration ORDER ripple through a dozen test files - it belongs here, once.
     */
    protected fun identify(
        requiredAcr: String? = null,
        intent: String? = null,
        availableTools: List<String>? = null
    ): String {
        val options = buildList {
            requiredAcr?.let { add(""""requiredAcr":"$it"""") }
            intent?.let { add(""""intent":"$it"""") }
            availableTools?.let { tools -> add(""""availableTools":[${tools.joinToString(",") { "\"$it\"" }}]""") }
        }
        val body = options.takeIf { it.isNotEmpty() }?.joinToString(",", "{", "}")
        val channelSessionId = (
            if (body == null) post("/orchestrator/api/v1/app/channels")
            else post("/orchestrator/api/v1/app/channels", body)
            ).channel()["channelSessionId"] as String
        reIdentifyViaFsc(channelSessionId)
        return channelSessionId
    }

    /**
     * [identify] plus the mandatory address confirmation that REGISTER puts BEFORE any enrollment
     * (docs/04-orchestrierung.md, "Pflichten sind Zustände"): the entry point for every test whose
     * subject is what happens AFTER an account can start enrolling.
     */
    protected fun identifyAndConfirmEmail(
        requiredAcr: String? = null,
        intent: String? = null,
        availableTools: List<String>? = null
    ): String {
        val channelSessionId = identify(requiredAcr, intent, availableTools)
        confirmEmailIfRequested(channelSessionId)
        return channelSessionId
    }

    /**
     * Discharges the address step only if the journey is actually asking for it right now - a
     * second run into an account that already confirmed one never gets offered it again.
     */
    protected fun confirmEmailIfRequested(channelSessionId: String) {
        val current = get("/orchestrator/api/v1/channels/$channelSessionId").nextRaw()
        if (current["toolId"] == "confirm-email") confirmEmail(channelSessionId)
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

    /**
     * Runs confirm-email through to Completed on the given channel, returns the confirmed address.
     *
     * Reuses a confirm-email tool session the journey already has running instead of activating a
     * second one - the enroll-first REGISTER variant opens it as its own mandatory first step, and
     * a real client would likewise just follow the `next` it was handed.
     */
    protected fun confirmEmail(channelSessionId: String): String {
        val email = "max.mustermann+${UUID.randomUUID()}@example.com"
        val current = get("/orchestrator/api/v1/channels/$channelSessionId").nextRaw()
        val confirmToolSessionId = (current["toolSessionId"] as? String)?.takeIf { current["toolId"] == "confirm-email" }
            ?: post("/orchestrator/api/v1/channels/$channelSessionId/tools/confirm-email").nextRaw()["toolSessionId"] as String
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"email":"$email"}""")
        }
        patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"code":"$code"}""")
        return email
    }

    /**
     * Activates email as an authentication METHOD - a one shot, since confirm-email already proved
     * control over the address. Separate from [confirmEmail] on purpose: confirming is account
     * infrastructure, enrolling is a login method.
     */
    protected fun enrollEmailMethod(channelSessionId: String) {
        post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email")
    }

    /** Runs enroll-password through to Completed - mandatory in every REGISTER run since the split. */
    protected fun enrollPassword(channelSessionId: String, password: String = "correct-horse-battery") {
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-password", """{"password":"$password"}""")
    }

    /** Runs enroll-sms through to Completed on the given channel. */
    protected fun enrollSms(channelSessionId: String) {
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
    }

    /**
     * Seeds an account whose only login method is sms (loa1), bound to this test's device, and
     * returns its account id. Built through the domain services ([AccountFixtures]), not by
     * replaying the registration click path: these tests need the account to EXIST, they do not
     * test how it came to be.
     */
    protected fun registerWithSmsOnly(): Long =
        accountFixtures.seedAccount(
            methods = listOf(AccountFixtures.Method.Sms()),
            bindDeviceKeyRef = currentBindingKeyRef
        )

    /** Runs auth-sms through to Completed on the given channel and returns the final response. */
    protected fun authenticateViaSms(channelSessionId: String): Map<String, Any?> {
        val (tan, activation) = captureMockTan {
            post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        return patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
    }

    /** Runs auth-password through to Completed on the given channel and returns the final response. */
    protected fun authenticateViaPassword(
        channelSessionId: String,
        password: String = "correct-horse-battery"
    ): Map<String, Any?> {
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password")
            .nextRaw()["toolSessionId"] as String
        return patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"$password"}""")
    }

    /**
     * Seeds the account [registerAndAuthenticate] would leave behind - sms + password, confirmed
     * address, bound to this test's device - WITHOUT opening or authenticating a channel.
     *
     * For the many tests that only need the account to exist ("a returning user"), and open their
     * own channel afterwards. Cheaper and, more importantly, independent of the registration
     * journey's step order.
     */
    protected fun seedRegisteredAccount(): Long =
        accountFixtures.seedAccount(
            methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password()),
            bindDeviceKeyRef = currentBindingKeyRef
        )

    /**
     * Seeds a registered account and logs INTO it on a fresh loa2 channel (sms + password),
     * returning the channelSessionId - the "returning user on a known device" precondition.
     *
     * Note this is deliberately NOT the same channel state as [registerAndAuthenticate]: a login
     * establishes amr [sms, password], a registration additionally carries its own `fsc`
     * identification evidence. Tests that depend on the latter must keep using the real journey.
     */
    protected fun loginAsSeededAccount(): String {
        seedRegisteredAccount()
        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            .channel()["channelSessionId"] as String
        authenticateViaSms(channelSessionId)
        authenticateViaPassword(channelSessionId)
        return channelSessionId
    }

    /**
     * Runs ident-fsc + confirm-email + enroll-sms + enroll-password through to AUTHENTICATED,
     * returns the channelSessionId. The address is confirmed even though sms alone already reaches
     * the default loa1 floor: a confirmed email is a Required Action of REGISTRATION
     * (docs/04-orchestrierung.md #2), not just an ACR-driven candidate.
     */
    protected fun registerAndAuthenticate(): String {
        val channelSessionId = identify()
        // The obligations in their reachable order: the address first - it is account
        // infrastructure and gates enroll-password, so it is asked for before any method is
        // offered (AuthEnrollCore.confirmEmail) - then a login method, then the password, which is
        // now required on every channel.
        confirmEmail(channelSessionId)
        enrollSms(channelSessionId)
        enrollPassword(channelSessionId)
        return channelSessionId
    }

    /**
     * Seeds an account with sms + password (plus, optionally, email as a login method) bound to
     * this test's device, and returns its confirmed address. Domain-service seeding rather than a
     * click path - see [AccountFixtures].
     */
    protected fun registerWithEmailAndPassword(
        password: String = "correct-horse-battery",
        /** Also activate email as a LOGIN method - its own act since ADR-17, needed by auth-email*. */
        alsoEnrollEmailMethod: Boolean = false
    ): String {
        accountFixtures.seedAccount(
            methods = buildList {
                add(AccountFixtures.Method.Sms())
                add(AccountFixtures.Method.Password(password))
                if (alsoEnrollEmailMethod) add(AccountFixtures.Method.Email)
            },
            bindDeviceKeyRef = currentBindingKeyRef
        )
        return AccountFixtures.EMAIL
    }
}

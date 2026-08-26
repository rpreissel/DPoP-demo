package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopValidator
import io.kotest.core.spec.style.BehaviorSpec
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestTemplate

/**
 * Shared HTTP-client/DB-reset plumbing for every orchestrator integration test. The 5 concrete
 * suites used to independently duplicate this near-verbatim: RestTemplate setup, resetDatabase's
 * table list, headers(), post/patch/get/delete, and the response-envelope Map extension helpers.
 *
 * Mock wiring for what DpopValidator's stub actually returns differs per suite (most stub a fake
 * JWK; DeviceBindingIntegrationTest needs the real JwkThumbprintService, so it supplies a real EC
 * key instead) and stays local to each subclass's own `beforeEach`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestSupport : BehaviorSpec() {

    @LocalServerPort
    protected var port: Int = 0

    @MockitoBean
    protected lateinit var dpopValidator: DpopValidator

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    // The JDK's default request factory can't send PATCH; HttpClient5 (already a test dep) can.
    protected val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    protected val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    protected var currentBindingKeyRef: String = ""

    init {
        beforeEach {
            // Children first (FK order); person/fsc_code seed data is left untouched. The union
            // of every table any of the 5 suites ever touches - deleting from one a given test
            // never populated is a harmless no-op.
            listOf(
                "id_fsc_tool_data", "enroll_sms_tool_data", "auth_sms_use_tool_data",
                "auth_sms_lookup_tool_data", "enroll_password_tool_data", "auth_password_use_tool_data",
                "auth_password_lookup_tool_data", "enroll_email_tool_data", "auth_email_use_tool_data",
                "auth_device_tool_data", "enroll_device_tool_data", "device_enrollment",
                "tool_session", "auth_journey", "session_event",
                "channel_session", "auth_context", "account", "auth_sms", "auth_password",
                "device_account_link", "login_attempt_throttle"
            ).forEach { jdbcTemplate.update("DELETE FROM $it") }
        }
    }

    protected fun headers(): HttpHeaders = HttpHeaders().apply {
        set("DPoP", "mock-dpop-token")
        set("Content-Type", "application/json")
    }

    protected fun post(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, headers()), mapType
        ).let { assertThat(it.statusCode.is2xxSuccessful).isTrue(); it.body!! }

    protected fun patch(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), mapType
        ).let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    protected fun get(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.GET, HttpEntity<Void>(headers()), mapType
        ).let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

    protected fun delete(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), mapType
        ).let { assertThat(it.statusCode).isEqualTo(HttpStatus.OK); it.body!! }

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
}

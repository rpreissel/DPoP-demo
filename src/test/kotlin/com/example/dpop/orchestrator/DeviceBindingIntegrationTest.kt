package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
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
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Date
import java.util.UUID

private val MAP_TYPE = object : ParameterizedTypeReference<Map<String, Any?>>() {}

/**
 * Dedicated test class rather than an extension of the (already large)
 * RegistrationLoginStepUpFlowIntegrationTest: enroll-device/auth-device need real ECDSA
 * signing/verification, which that class's `@MockitoBean JwkThumbprintService` would silently
 * short-circuit (every unstubbed call returns the same default value, defeating the point of
 * testing key-binding correctness). Here only DpopValidator (the per-request CHANNEL proof) is
 * mocked - JwkThumbprintService and DeviceProofValidator run for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeviceBindingIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var dpopValidator: DpopValidator

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    // Real EC key standing in for the device's DPoP channel key - real, not a Mockito mock, so the
    // real (unmocked) JwkThumbprintService can compute a real, consistent bindingKeyRef from it.
    private val channelKey = ECKeyGenerator(Curve.P_256).generate()

    @BeforeEach
    fun resetDatabase() {
        listOf(
            "auth_device_tool_data", "enroll_device_tool_data", "device_enrollment",
            "auth_sms_use_tool_data", "enroll_sms_tool_data", "auth_sms",
            "id_fsc_tool_data", "tool_session", "process_session", "session_event",
            "channel_session", "auth_context", "account", "device_account_link", "login_attempt_throttle"
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    @BeforeEach
    fun resetMocks() {
        `when`(dpopValidator.validate(anyString(), anyString(), anyString())).thenAnswer {
            DpopProof(
                token = "mock-token",
                publicKey = channelKey.toPublicJWK(),
                jti = UUID.randomUUID().toString(),
                htm = "POST",
                htu = "http://localhost/mock",
                issuedAt = Instant.now(),
                nonce = null
            )
        }
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
        val tan = Regex("""TAN (\d{6}) an""").find(buffer.toString())?.groupValues?.get(1)
            ?: error("No mock TAN found in captured output: ${buffer}")
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

    /** Self-signed device-proof JWT (typ=device-proof+jwt), same shape DeviceProofValidator expects. */
    private fun signDeviceProof(deviceKey: ECKey, htu: String, accessMeans: String, issuedAt: Date = Date()): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(deviceKey.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(issuedAt)
            .claim("htm", "PATCH")
            .claim("htu", htu)
            .claim("accessMeans", accessMeans)
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(deviceKey.toECPrivateKey()))
        return signedJWT.serialize()
    }

    private fun enrollDevice(channelSessionId: String, accessMeans: String = "biometric"): ECKey {
        val deviceKey = ECKeyGenerator(Curve.P_256).generate()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", accessMeans)
        patch(patchUrl, """{"deviceProof":"$proof"}""")
        return deviceKey
    }

    @Test
    fun enrollDevice_reachesLoa2DirectlyWithGeraetAndAccessMeansInAmr() {
        val channelSessionId = identify()
        val deviceKey = enrollDevice(channelSessionId, accessMeans = "biometric")
        assertThat(deviceKey).isNotNull()

        val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
        assertThat(channel.channel()["currentAcr"]).isEqualTo("loa2")
        // "fsc" is also present (ident-fsc's own amr, accumulated across the whole session) -
        // only device/biometric are asserted here.
        @Suppress("UNCHECKED_CAST")
        assertThat(channel.channel()["currentAmr"] as List<String>).contains("device", "biometric")
        @Suppress("UNCHECKED_CAST")
        assertThat((channel.channel()["activeMethods"] as List<*>).methodNames()).contains("device")
    }

    @Test
    fun authDevice_withTheEnrolledKey_recognizesTheDeviceAndReachesLoa2OnANewChannel() {
        val channelSessionId = identify()
        val deviceKey = enrollDevice(channelSessionId)

        // Same DPoP binding key (channelKey) -> a brand-new channel recognizes the device via
        // DeviceAccountLink and offers auth-device straight away, single-candidate skip.
        val newChannel = post("/orchestrator/api/v1/app/channels")
        assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth"))
        val newChannelSessionId = newChannel.channel()["channelSessionId"] as String

        val authToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-device").nextRaw()["toolSessionId"] as String
        val authPatchUrl = "/orchestrator/api/v1/tools/$authToolSessionId/auth-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$authPatchUrl", "pin")
        val authenticated = patch(authPatchUrl, """{"deviceProof":"$proof"}""")
        assertThat(authenticated.next()).isEqualTo(mapOf("type" to "flow", "context" to "authentication", "step" to "authenticated"))

        val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa2")
        @Suppress("UNCHECKED_CAST")
        assertThat(afterLogin.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("device", "pin")
    }

    @Test
    fun authDevice_signedWithADifferentKey_isRejectedAsFailedNotAsSomeoneElsesCredential() {
        val channelSessionId = identify()
        enrollDevice(channelSessionId)

        val newChannel = post("/orchestrator/api/v1/app/channels")
        val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
        val authToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-device").nextRaw()["toolSessionId"] as String
        val authPatchUrl = "/orchestrator/api/v1/tools/$authToolSessionId/auth-device"

        val wrongKey = ECKeyGenerator(Curve.P_256).generate()
        val proof = signDeviceProof(wrongKey, "http://localhost:$port$authPatchUrl", "pin")
        val result = patch(authPatchUrl, """{"deviceProof":"$proof"}""")

        // Failed, not Completed: retried in place, no error leaking which device WAS expected.
        assertThat(result.stepData()["error"]).isEqualTo("Geraet nicht erkannt")
        assertThat(result.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth"))
    }

    @Test
    fun enrollDevice_withAnExpiredProof_isRejectedAsUnauthorized() {
        val channelSessionId = identify()
        val deviceKey = ECKeyGenerator(Curve.P_256).generate()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val staleProof = signDeviceProof(
            deviceKey, "http://localhost:$port$patchUrl", "pin",
            issuedAt = Date.from(Instant.now().minusSeconds(600))
        )

        val exception = org.junit.jupiter.api.assertThrows<HttpClientErrorException> {
            restTemplate.exchange(
                "http://localhost:$port$patchUrl", HttpMethod.PATCH,
                HttpEntity("""{"deviceProof":"$staleProof"}""", headers()), MAP_TYPE
            )
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun enrollDevice_replayingTheSameProof_isRejectedBecauseTheToolSessionIsNoLongerCurrent() {
        // A successfully completed enroll-device tool session is done - the process has already
        // moved on, so requireCurrentTool rejects a second PATCH before DeviceProofValidator's
        // own jti+thumbprint replay protection would even get a chance to fire. Session lifecycle
        // is the FIRST line of defense against replay here; the crypto-level replay check is
        // defense-in-depth for scenarios where the URL itself could otherwise be hit twice, same
        // posture already accepted for ordinary DPoP proofs in this app.
        val channelSessionId = identify()
        val deviceKey = ECKeyGenerator(Curve.P_256).generate()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", "pin")

        patch(patchUrl, """{"deviceProof":"$proof"}""")

        val exception = org.junit.jupiter.api.assertThrows<HttpClientErrorException> {
            restTemplate.exchange(
                "http://localhost:$port$patchUrl", HttpMethod.PATCH,
                HttpEntity("""{"deviceProof":"$proof"}""", headers()), MAP_TYPE
            )
        }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun manageMethods_enrollDeviceRequiresLoa2First_evenThoughTheChannelIsAlreadyAuthenticated() {
        // Register+enroll via sms only (loa1) - deliberately NOT via enroll-device, so the
        // session's own currentAcr stays loa1 after login.
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")

        val newChannel = post("/orchestrator/api/v1/app/channels")
        val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
        val (loginTan, activation) = captureMockTan {
            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")

        val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
        assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa1")

        // MANAGE_METHODS with enroll-device as the goal must still force the loa2 step-up gate
        // first (ChannelService.MANAGE_METHODS_REQUIRED_ACR) - the session's own loa1 evidence is
        // not enough to add a loa2-capable credential on its own authority.
        val started = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
        assertThat(started.channel()["state"]).isEqualTo("STEP_UP_IN_PROGRESS")
        assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
    }
}

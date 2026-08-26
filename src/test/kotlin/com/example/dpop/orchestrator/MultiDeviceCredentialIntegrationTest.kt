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
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.Date
import java.util.UUID

private val MAP_TYPE = object : ParameterizedTypeReference<Map<String, Any?>>() {}

/**
 * Several physical devices, each with their own enroll-device credential, on the same account
 * (docs/03-tool-architektur.md, allowsMultipleInstances). Dedicated file, same reasoning as
 * DeviceBindingIntegrationTest: needs real ECDSA signing, which a mocked JwkThumbprintService
 * would silently defeat.
 *
 * Unlike DeviceBindingIntegrationTest, the CHANNEL's own DPoP key (device fingerprint) varies
 * per test via [currentChannelKey] - dpopValidator is mocked to return whichever key is
 * "current", and the real (unmocked) JwkThumbprintService computes a real, distinct bindingKeyRef
 * per key, simulating two genuinely different physical devices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MultiDeviceCredentialIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var dpopValidator: DpopValidator

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory())

    private var currentChannelKey: ECKey = ECKeyGenerator(Curve.P_256).generate()

    @BeforeEach
    fun resetDatabase() {
        listOf(
            "auth_device_tool_data", "enroll_device_tool_data", "device_enrollment",
            "id_fsc_tool_data", "tool_session", "auth_journey", "session_event",
            "channel_session", "auth_context", "account", "device_account_link", "login_attempt_throttle"
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    @BeforeEach
    fun resetMocks() {
        `when`(dpopValidator.validate(anyString(), anyString(), anyString())).thenAnswer {
            DpopProof(
                token = "mock-token",
                publicKey = currentChannelKey.toPublicJWK(),
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

    private fun identify(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        return channelSessionId
    }

    private fun signDeviceProof(deviceKey: ECKey, htu: String, userVerification: String, issuedAt: Date = Date()): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(deviceKey.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(issuedAt)
            .claim("htm", "PATCH")
            .claim("htu", htu)
            .claim("userVerification", userVerification)
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(deviceKey.toECPrivateKey()))
        return signedJWT.serialize()
    }

    private fun enrollDevice(channelSessionId: String, deviceKey: ECKey, label: String): Map<String, Any?> {
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", "biometric")
        return patch(patchUrl, """{"deviceProof":"$proof","label":"$label"}""")
    }

    @Test
    fun twoDevicesCanEachHoldTheirOwnActiveCredential_withoutDeactivatingEachOther() {
        // Device A registers the account and enrolls its own key.
        val deviceAKey = ECKeyGenerator(Curve.P_256).generate()
        currentChannelKey = deviceAKey
        val channelASessionId = identify()
        enrollDevice(channelASessionId, deviceAKey, "Laptop")

        // Device B: a different physical device (different bindingKeyRef, never linked before) -
        // re-identifies into the SAME account (same KVNR -> findOrCreateAccount reuses it) and
        // enrolls its OWN key. Must NOT deactivate device A's credential.
        val deviceBKey = ECKeyGenerator(Curve.P_256).generate()
        currentChannelKey = deviceBKey
        val channelBSessionId = identify()
        enrollDevice(channelBSessionId, deviceBKey, "Handy")

        @Suppress("UNCHECKED_CAST")
        val methods = get("/orchestrator/api/v1/app/channels/$channelBSessionId/methods")["methods"] as List<Map<String, Any?>>
        val deviceEntries = methods.filter { it["method"] == "device" }
        assertThat(deviceEntries).hasSize(2)
        assertThat(deviceEntries.map { it["label"] }).containsExactlyInAnyOrder("Laptop", "Handy")
    }

    @Test
    fun authDeviceIsOnlyOfferedAndResolvableOnTheDeviceHoldingTheMatchingKey() {
        val deviceAKey = ECKeyGenerator(Curve.P_256).generate()
        currentChannelKey = deviceAKey
        val channelASessionId = identify()
        enrollDevice(channelASessionId, deviceAKey, "Laptop")

        // Same device (key A) again, fresh channel: DeviceAccountLink recognizes it, auth-device
        // offered directly (single active method, single candidate skip).
        val secondChannelOnDeviceA = post("/orchestrator/api/v1/app/channels")
        assertThat(secondChannelOnDeviceA.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth"))

        // Device B (different key, never enrolled its own credential) re-identifies into the same
        // account - canAccountReach is true (device-agnostic: the account HAS a loa2 method), but
        // candidateTools must filter device A's instance out (wrong bindingKeyRef) and fall back
        // to enrollment instead of dead-ending - enroll-device is offered so device B can register
        // its own key, never auth-device for a key it doesn't hold.
        val deviceBKey = ECKeyGenerator(Curve.P_256).generate()
        currentChannelKey = deviceBKey
        val channelBSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelBSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        val reidentified = patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        // sms/email/enroll-device are all still legitimate candidates (only "device" is active on
        // this account, on a key device B doesn't hold) - a selection page, never auth-device.
        assertThat(reidentified.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
        @Suppress("UNCHECKED_CAST")
        val options = reidentified["stepData"].let { (it as Map<String, Any?>)["options"] as List<String> }
        assertThat(options).contains("enroll-device").doesNotContain("auth-device")
    }
}

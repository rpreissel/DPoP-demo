package com.example.dpop.orchestrator

import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.orchestrator.dpop.DpopProof
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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Dedicated test class rather than an extension of the (already large)
 * RegistrationLoginStepUpFlowIntegrationTest: enroll-device/auth-device need real ECDSA
 * signing/verification, which that class's mocked JwkThumbprintService would silently
 * short-circuit (every unstubbed call returns the same default value, defeating the point of
 * testing key-binding correctness). Here only DpopValidator (the per-request CHANNEL proof) is
 * mocked - JwkThumbprintService and DeviceProofValidator run for real.
 */
class DeviceBindingIntegrationTest : IntegrationTestSupport() {

    // Real EC key standing in for the device's DPoP channel key - real, not a Mockito mock, so the
    // real (unmocked) JwkThumbprintService can compute a real, consistent bindingKeyRef from it.
    private val channelKey = ECKeyGenerator(Curve.P_256).generate()

    init {
        beforeEach {
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
    }

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
    private fun enrollSms(channelSessionId: String) {
        val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-sms", """{"tan":"$tan"}""")
    }
    private fun enrollEmail(channelSessionId: String) {
        val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        // The mock email gateway words its log line differently from the SMS one, so this reads
        // the code out of the response's demo block instead of the captured output.
        val sent = patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-email", """{"email":"$DEMO_EMAIL"}""")
        @Suppress("UNCHECKED_CAST")
        val code = (sent["demo"] as Map<String, Any?>)["tan"] as String
        patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-email", """{"code":"$code"}""")
    }
    /** Self-signed device-proof JWT (typ=device-proof+jwt), same shape DeviceProofValidator expects. */
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
    private fun enrollDevice(channelSessionId: String, userVerification: String = "biometric"): ECKey {
        val deviceKey = ECKeyGenerator(Curve.P_256).generate()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", userVerification)
        patch(patchUrl, """{"deviceProof":"$proof"}""")
        return deviceKey
    }

    init {
        given("enroll-device and auth-device with real ECDSA signing") {
        then("Enroll device reaches loa 2 directly with geraet and access means in amr") {
            val channelSessionId = identify()
            val deviceKey = enrollDevice(channelSessionId, userVerification = "biometric")
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
        then("Auth device with the enrolled key recognizes the device and reaches loa 2 on a new channel") {
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
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa2")
            @Suppress("UNCHECKED_CAST")
            assertThat(afterLogin.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("device", "pin")
        }
        then("Auth device declined on a fresh channel falls through to the remaining auth methods") {
            // Registration enrols sms (and the email obligation adds email), then a device credential
            // on top - so declining the device leaves genuine alternatives to choose from.
            val channelSessionId = identify()
            enrollSms(channelSessionId)
            enrollEmail(channelSessionId)
            // sms + confirmed email already finish the journey, so the device credential is added
            // afterwards through MANAGE - the loa2 gate is satisfied by this session's own ident-fsc.
            post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
            enrollDevice(channelSessionId)

            // Fresh session on the same device: the first state of the FAST fallback chain, the device method.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
            assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth"))

            // Declining it is NOT cancelling the journey: the chain falls through to the other
            // methods the account actually has, which is a real choice rather than the same screen.
            val toolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-device").nextRaw()["toolSessionId"] as String
            val afterDecline = delete("/orchestrator/api/v1/tools/$toolSessionId/auth-device")
            assertThat(afterDecline.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(afterDecline.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")

            // Cancelling the whole journey, by contrast, restarts the SAME intent - and therefore
            // legitimately lands back on the first state. The two actions are not interchangeable.
            val afterCancel = delete("/orchestrator/api/v1/app/channels/$newChannelSessionId/process")
            assertThat(afterCancel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth"))
        }
        then("Auth device signed with a different key is rejected as failed not as someone elses credential") {
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
        then("Enroll device with an expired proof is rejected as unauthorized") {
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
                    HttpEntity("""{"deviceProof":"$staleProof"}""", headers()), mapType
                )
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        }
        then("Enroll device replaying the same proof is rejected because the tool session is no longer current") {
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
                    HttpEntity("""{"deviceProof":"$proof"}""", headers()), mapType
                )
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
        then("Manage methods enroll device requires loa 2 first even though the channel is already authenticated") {
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

            // MANAGE with enroll-device as the goal must still force the loa2 step-up gate
            // first (ManageStrategy.REQUIRED_ACR) - the session's own loa1 evidence is
            // not enough to add a loa2-capable credential on its own authority.
            val started = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
            assertThat(started.channel()["state"]).isEqualTo("STEP_UP_IN_PROGRESS")
            assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        }
        }
    }
}

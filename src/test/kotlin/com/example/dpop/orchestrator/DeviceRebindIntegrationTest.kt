package com.example.dpop.orchestrator

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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * REGISTER on a device already linked to a different account (docs/04-orchestrierung.md #2,
 * "Zweitaccount") no longer silently overwrites `DeviceAccountLink` - it asks first
 * (RegisterState.ConfirmDeviceRebind), as early as possible (right after identification, before
 * any method is offered), and never aborts the journey either way: accepting rebinds and revokes
 * the previous account's own device-bound credential for this key, declining cancels the journey
 * cleanly while leaving the old binding untouched. Real ECDSA signing, same reasoning as
 * DeviceBindingIntegrationTest.
 */
class DeviceRebindIntegrationTest : IntegrationTestSupport() {

    private val channelKey = ECKeyGenerator(Curve.P_256).generate()

    init {
        beforeEach {
            every { dpopValidator.validate(any(), any(), any()) } returns DpopProof(
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

    private fun signDeviceProof(deviceKey: ECKey, htu: String): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("device-proof+jwt")).jwk(deviceKey.toPublicJWK()).build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date())
            .claim("htm", "PATCH")
            .claim("htu", htu)
            .claim("userVerification", "biometric")
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(deviceKey.toECPrivateKey()))
        return signedJWT.serialize()
    }

    private fun enrollDevice(channelSessionId: String): ECKey {
        val deviceKey = ECKeyGenerator(Curve.P_256).generate()
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        patch(patchUrl, """{"deviceProof":"${signDeviceProof(deviceKey, "http://localhost:$port$patchUrl")}"}""")
        return deviceKey
    }

    /** Registers as a SECOND, distinct person (own KVNR) on the SAME physical device (channelKey). */
    private fun identifyAsSecondPerson(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"B987654321","name":"Beispiel","vorname":"Erika","fsc":"ERIKA123"}"""
        )
        return channelSessionId
    }

    init {
        given("a device already linked to account A, with A's own device credential") {
            `when`("a fresh REGISTER identifies a different person B on the same device") {
                then("asks for confirmation right after identification, before any method is offered") {
                    val channelA = identify()
                    enrollDevice(channelA)

                    val channelB = identifyAsSecondPerson()
                    val afterIdent = get("/orchestrator/api/v1/channels/$channelB")
                    afterIdent.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }

                then("accepting rebinds the device and revokes A's device credential") {
                    val channelA = identify()
                    enrollDevice(channelA)

                    val channelB = identifyAsSecondPerson()
                    val accepted = post("/orchestrator/api/v1/channels/$channelB/answer", """{"answer":"accept"}""")
                    // Continues normally afterwards - never a dead end.
                    accepted.next().shouldNotBeNull()

                    val deviceLink = get("/orchestrator/api/v1/app/channels/device-link")
                    deviceLink["linked"] shouldBe true

                    // A's own device credential is ACTIVELY deactivated and its enrollment deleted
                    // (Phase 3), not just hidden from matching (Phase 1 alone would leave it "active"
                    // in the account's own method list, just unofferable on this device) - re-identify
                    // as A and read the account's methods directly, unfiltered by device.
                    val channelAAgain = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                    reIdentifyViaFsc(channelAAgain)
                    @Suppress("UNCHECKED_CAST")
                    val methods = get("/orchestrator/api/v1/channels/$channelAAgain/methods")["methods"] as List<Map<String, Any?>>
                    methods.none { it["method"] == "device" } shouldBe true
                }

                then("declining cancels the journey outright and leaves the old binding untouched") {
                    val channelA = identify()
                    enrollDevice(channelA)
                    val deviceLinkBefore = get("/orchestrator/api/v1/app/channels/device-link")

                    val channelB = identifyAsSecondPerson()
                    val declined = post("/orchestrator/api/v1/channels/$channelB/answer", """{"answer":"decline"}""")
                    // Cancel restarts the SAME entry intent (REGISTER) fresh - not an error.
                    declined.channel()["state"] shouldBe "REGISTERING"

                    val deviceLinkAfter = get("/orchestrator/api/v1/app/channels/device-link")
                    deviceLinkAfter shouldBe deviceLinkBefore
                }
            }
        }
    }
}

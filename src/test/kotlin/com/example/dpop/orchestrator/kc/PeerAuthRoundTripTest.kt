package com.example.dpop.orchestrator.kc

import com.example.dpop.orchestrator.dpop.DpopReplayProtectionService
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.util.Date
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A signing key that exists only in the test source set - production has no key issuer of its own. */
private val TEST_PEER_AUTH_KEY: ECKey = ECKeyGenerator(Curve.P_256)
    .keyID("test-peer-auth-1")
    .algorithm(JWSAlgorithm.ES256)
    .keyUse(KeyUse.SIGNATURE)
    .generate()

/** Serves [TEST_PEER_AUTH_KEY]'s public half the way the real realm serves its orchestrator-jwks. */
@TestConfiguration
class TestPeerAuthJwksConfig {
    @RestController
    class TestPeerAuthJwksController {
        @GetMapping("/test-peer-auth/jwks.json")
        fun jwks(): Map<String, Any> = JWKSet(TEST_PEER_AUTH_KEY.toPublicJWK()).toJSONObject()
    }
}

/**
 * Signs a real assertion with a test-only key and drives it through a [PeerAuthValidator]/
 * [KeycloakJwkSource] pair pointed at a JWKS endpoint of the running test server - unlike
 * [com.example.dpop.orchestrator.KcChannelIntegrationTest], which mocks [PeerAuthValidator] to
 * isolate the journey logic, this proves the sign -> fetch -> verify round trip the real Keycloak
 * extension depends on. Built directly (not autowired): the app's own `kc.peer-auth` has no issuer
 * outside the `keycloak` profile, and this test boots on a random port anyway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPeerAuthJwksConfig::class)
class PeerAuthRoundTripTest : BehaviorSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var replayProtectionService: DpopReplayProtectionService

    private fun sign(key: ECKey, htm: String, htu: String, channelAnchor: String): String {
        val claims = JWTClaimsSet.Builder()
            .issuer("test-issuer")
            .audience("dpop-demo-orchestrator")
            .claim("htm", htm)
            .claim("htu", htu)
            .claim("channel_anchor", channelAnchor)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date())
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(PeerAuthValidator.ASSERTION_TYPE).keyID(key.keyID).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    init {
        Given("a peer-auth assertion signed with a key the JWKS endpoint publishes") {
            When("a PeerAuthValidator pointed at that jwks.json validates it") {
                Then("it verifies successfully via the real sign -> fetch -> verify round trip") {
                    val jwkSource = KeycloakJwkSource(
                        jwksUri = "http://localhost:$port/test-peer-auth/jwks.json",
                        cacheTtlSeconds = 600
                    )
                    val validator = PeerAuthValidator(
                        jwkSource = jwkSource,
                        replayProtectionService = replayProtectionService,
                        expectedIssuer = "test-issuer",
                        expectedAudience = "dpop-demo-orchestrator",
                        maxClockSkewSeconds = 30,
                        maxAssertionAgeSeconds = 30
                    )
                    val htu = "http://localhost:$port/orchestrator/api/v1/kc/channels/${UUID.randomUUID()}"
                    val token = sign(TEST_PEER_AUTH_KEY, "PATCH", htu, "channel-anchor-${UUID.randomUUID()}")

                    val assertion = validator.validate(token, "PATCH", htu)

                    assertion.shouldNotBeNull()
                    assertion.channelAnchor.shouldNotBeNull()
                }
            }
        }
    }
}

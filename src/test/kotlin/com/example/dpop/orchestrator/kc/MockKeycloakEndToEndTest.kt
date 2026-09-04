package com.example.dpop.orchestrator.kc

import com.example.dpop.orchestrator.dpop.DpopReplayProtectionService
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Date
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles

/**
 * Signs a real assertion with the Mock-Keycloak signing key (bd DPoP-demo-f9o.9) and drives it
 * through a [PeerAuthValidator]/[KeycloakJwkSource] pair pointed at the actually-running test
 * server's own `/mock-keycloak/.well-known/jwks.json` - unlike [KcChannelIntegrationTest], which
 * mocks [PeerAuthValidator] to isolate the journey logic, this proves the real signing key ->
 * jwks.json -> signature-verification round trip the frontend will depend on. Built directly
 * (not autowired) because the app's own `kc.peer-auth.jwks-uri` config is fixed to port 8080,
 * while this test boots on a random one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MockKeycloakEndToEndTest : BehaviorSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var keyProvider: MockKeycloakKeyProvider

    @Autowired
    private lateinit var replayProtectionService: DpopReplayProtectionService

    private fun sign(key: ECKey, htm: String, htu: String, kcAuthSessionId: String): String {
        val claims = JWTClaimsSet.Builder()
            .issuer("mock-keycloak")
            .audience("dpop-demo-orchestrator")
            .claim("htm", htm)
            .claim("htu", htu)
            .claim("kc_auth_session_id", kcAuthSessionId)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date())
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    init {
        Given("a peer-auth assertion signed with the mock-keycloak key") {
            When("a PeerAuthValidator pointed at this server's own jwks.json validates it") {
                Then("it verifies successfully via the real sign -> fetch -> verify round trip") {
                    val jwkSource = KeycloakJwkSource(
                        jwksUri = "http://localhost:$port/mock-keycloak/.well-known/jwks.json",
                        cacheTtlSeconds = 600
                    )
                    val validator = PeerAuthValidator(
                        jwkSource = jwkSource,
                        replayProtectionService = replayProtectionService,
                        expectedIssuer = "mock-keycloak",
                        expectedAudience = "dpop-demo-orchestrator",
                        maxClockSkewSeconds = 30,
                        maxAssertionAgeSeconds = 30
                    )
                    val htu = "http://localhost:$port/orchestrator/api/v1/kc/channels/${UUID.randomUUID()}"
                    val token = sign(keyProvider.key, "PATCH", htu, "kc-auth-session-${UUID.randomUUID()}")

                    val assertion = validator.validate(token, "PATCH", htu)

                    assertion.shouldNotBeNull()
                    assertion.kcAuthSessionId.shouldNotBeNull()
                }
            }
        }
    }
}

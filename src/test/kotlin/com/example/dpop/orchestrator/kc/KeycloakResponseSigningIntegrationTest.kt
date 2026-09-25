package com.example.dpop.orchestrator.kc

import com.example.dpop.orchestrator.IntegrationTestSupport
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Review 2026-09, M-9: every answer to a peer-auth request carries the orchestrator's signature over
 * exactly this status and body, bound to the request's jti - checkable against the published JWKS.
 */
class KeycloakResponseSigningIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }

        given("a peer-auth request from Keycloak") {
            then("the answer is signed for exactly this request, status and body") {
                val channelSessionId = UUID.randomUUID()
                val jti = UUID.randomUUID().toString()
                every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
                    jti = jti, issuedAt = Instant.now(), channelAnchor = channelSessionId.toString(), subject = null
                )
                val assertion = SignedJWT(
                    JWSHeader(JWSAlgorithm.ES256),
                    JWTClaimsSet.Builder().issuer("kc-test").audience("orch-test").jwtID(jti)
                        .issueTime(Date()).claim("channel_anchor", channelSessionId.toString()).build()
                ).apply { sign(ECDSASigner(ECKeyGenerator(Curve.P_256).generate())) }.serialize()

                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId",
                    HttpMethod.PATCH,
                    HttpEntity(withDefaultAvailableTools("{}"), HttpHeaders().apply {
                        set("Authorization", "Bearer $assertion")
                        set("Content-Type", "application/json")
                    }),
                    String::class.java
                )

                val signature = response.headers.getFirst(KeycloakResponseSigner.HEADER)
                signature shouldNotBe null
                val jwt = SignedJWT.parse(signature)
                val jwks = restTemplate.getForObject("http://localhost:$port$RESPONSE_JWKS_PATH", String::class.java)
                val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as ECKey
                jwt.verify(ECDSAVerifier(key)) shouldBe true

                val claims = jwt.jwtClaimsSet
                claims.getStringClaim("req") shouldBe jti
                claims.issuer shouldBe "orch-test"
                claims.audience shouldBe listOf("kc-test")
                (claims.getClaim("status") as Number).toInt() shouldBe response.statusCode.value()
                claims.getStringClaim("body_sha256") shouldBe
                    Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(response.body!!.toByteArray())).toString()
            }
        }

        given("an ordinary App request (DPoP, no peer-auth assertion)") {
            then("nothing is signed") {
                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                    HttpEntity(withDefaultAvailableTools("{}"), headers()), String::class.java
                )
                response.headers.getFirst(KeycloakResponseSigner.HEADER) shouldBe null
            }
        }
    }
}

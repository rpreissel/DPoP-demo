package com.example.dpop.orchestrator.kc

import com.example.dpop.orchestrator.dpop.DpopProofReplay
import com.example.dpop.orchestrator.dpop.DpopProofReplayRepository
import com.example.dpop.orchestrator.dpop.DpopReplayProtectionService
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Pure unit test of [PeerAuthValidator] - no Spring context, no HTTP layer. [KeycloakJwkSource]
 * is mocked to hand back the test's own key directly, same reasoning as [DpopValidatorTest]:
 * signature/claim-checking logic should be exercised without a real JWKS endpoint.
 */
class PeerAuthValidatorTest : BehaviorSpec({

    val issuer = "mock-keycloak"
    val audience = "dpop-demo-orchestrator"
    val method = "PATCH"
    val url = "https://example.test/orchestrator/api/v1/kc/channels/abc"
    val kid = "test-key"

    fun validator(jwkSource: KeycloakJwkSource) = PeerAuthValidator(
        jwkSource = jwkSource,
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository()),
        expectedIssuer = issuer,
        expectedAudience = audience,
        maxClockSkewSeconds = 30,
        maxAssertionAgeSeconds = 30
    )

    fun jwkSourceReturning(key: ECKey) = mockk<KeycloakJwkSource> {
        every { find(kid) } returns key.toPublicJWK()
    }

    fun signAssertion(
        key: ECKey,
        htm: String = method,
        htu: String = url,
        issuedAt: Date = Date(),
        jti: String? = UUID.randomUUID().toString(),
        iss: String? = issuer,
        aud: String? = audience,
        kcAuthSessionId: String? = "auth-session-1",
        kcSessionId: String? = null,
        subject: String? = null,
        algorithm: JWSAlgorithm = JWSAlgorithm.ES256,
        keyId: String = kid
    ): String {
        val header = JWSHeader.Builder(algorithm).keyID(keyId).build()
        val claimsBuilder = JWTClaimsSet.Builder()
            .issueTime(issuedAt)
            .claim("htm", htm)
            .claim("htu", htu)
        jti?.let { claimsBuilder.jwtID(it) }
        iss?.let { claimsBuilder.issuer(it) }
        aud?.let { claimsBuilder.audience(it) }
        kcAuthSessionId?.let { claimsBuilder.claim("kc_auth_session_id", it) }
        kcSessionId?.let { claimsBuilder.claim("kc_session_id", it) }
        subject?.let { claimsBuilder.subject(it) }
        val signedJWT = SignedJWT(header, claimsBuilder.build())
        signedJWT.sign(ECDSASigner(key.toECPrivateKey()))
        return signedJWT.serialize()
    }

    given("a validly signed, fresh initial-login assertion") {
        `when`("validating it") {
            then("it succeeds and reports the assertion's context") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val jti = UUID.randomUUID().toString()
                val assertion = signAssertion(key, jti = jti, kcAuthSessionId = "auth-session-42")

                val result = validator(jwkSourceReturning(key)).validate(assertion, method, url)

                result.jti shouldBe jti
                result.kcAuthSessionId shouldBe "auth-session-42"
                result.kcSessionId.shouldBeNull()
            }
        }
    }

    given("a validly signed step-up assertion") {
        then("kcSessionId and the subject come through, kcAuthSessionId does not") {
            val key = ECKeyGenerator(Curve.P_256).generate()
            val assertion = signAssertion(key, kcAuthSessionId = null, kcSessionId = "user-session-7", subject = "kc-sub-7")

            val result = validator(jwkSourceReturning(key)).validate(assertion, method, url)

            result.kcSessionId shouldBe "user-session-7"
            result.subject shouldBe "kc-sub-7"
            result.kcAuthSessionId.shouldBeNull()
        }
    }

    given("no assertion at all") {
        then("a null assertion is rejected as missing") {
            shouldThrow<PeerAuthValidationException> {
                validator(mockk()).validate(null, method, url)
            }
        }
        then("a blank assertion is rejected as missing") {
            shouldThrow<PeerAuthValidationException> {
                validator(mockk()).validate("   ", method, url)
            }
        }
    }

    given("an assertion that isn't a well-formed JWT at all") {
        then("it is rejected as an invalid format") {
            shouldThrow<PeerAuthValidationException> {
                validator(mockk()).validate("not-a-jwt", method, url)
            }
        }
    }

    given("an unsupported algorithm") {
        then("it is rejected before any JWKS lookup happens") {
            val rsaKey = RSAKeyGenerator(2048).keyID(kid).generate()
            val header = JWSHeader.Builder(JWSAlgorithm.RS512).keyID(kid).build()
            val jwt = SignedJWT(header, JWTClaimsSet.Builder().jwtID("j").issueTime(Date()).claim("htm", method).claim("htu", url).build())
            jwt.sign(RSASSASigner(rsaKey.toPrivateKey()))
            shouldThrow<PeerAuthValidationException> { validator(mockk()).validate(jwt.serialize(), method, url) }
        }
    }

    given("an RSA-signed assertion") {
        then("it is accepted like an EC-signed one, verified against the matching RSA key") {
            val rsaKey = RSAKeyGenerator(2048).keyID(kid).generate()
            val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build()
            val claims = JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date())
                .issuer(issuer)
                .audience(audience)
                .claim("htm", method)
                .claim("htu", url)
                .claim("kc_auth_session_id", "auth-session-rsa")
                .build()
            val jwt = SignedJWT(header, claims)
            jwt.sign(RSASSASigner(rsaKey.toPrivateKey()))
            val jwkSource = mockk<KeycloakJwkSource> { every { find(kid) } returns rsaKey.toPublicJWK() }

            val result = validator(jwkSource).validate(jwt.serialize(), method, url)

            result.kcAuthSessionId shouldBe "auth-session-rsa"
        }
    }

    given("a missing kid header") {
        then("it is rejected before any JWKS lookup happens") {
            val key = ECKeyGenerator(Curve.P_256).generate()
            val header = JWSHeader.Builder(JWSAlgorithm.ES256).build()
            val jwt = SignedJWT(header, JWTClaimsSet.Builder().jwtID("j").issueTime(Date()).claim("htm", method).claim("htu", url).build())
            jwt.sign(ECDSASigner(key.toECPrivateKey()))
            shouldThrow<PeerAuthValidationException> { validator(mockk()).validate(jwt.serialize(), method, url) }
        }
    }

    given("an unknown kid") {
        then("it is rejected") {
            val key = ECKeyGenerator(Curve.P_256).generate()
            val assertion = signAssertion(key)
            val jwkSource = mockk<KeycloakJwkSource> { every { find(kid) } returns null }
            shouldThrow<PeerAuthValidationException> { validator(jwkSource).validate(assertion, method, url) }
        }
    }

    given("a signature that doesn't match the key the kid resolves to") {
        then("it is rejected") {
            val signingKey = ECKeyGenerator(Curve.P_256).generate()
            val otherKey = ECKeyGenerator(Curve.P_256).generate()
            val assertion = signAssertion(signingKey)
            shouldThrow<PeerAuthValidationException> {
                validator(jwkSourceReturning(otherKey)).validate(assertion, method, url)
            }
        }
    }

    given("claim mismatches") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        then("the wrong issuer is rejected") {
            val assertion = signAssertion(key, iss = "someone-else")
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("the wrong audience is rejected") {
            val assertion = signAssertion(key, aud = "some-other-service")
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("a different HTTP method than the request is rejected") {
            val assertion = signAssertion(key, htm = "GET")
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("a different URL than the request is rejected") {
            val assertion = signAssertion(key, htu = "https://example.test/somewhere-else")
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("carrying neither kc_auth_session_id nor kc_session_id is rejected") {
            val assertion = signAssertion(key, kcAuthSessionId = null, kcSessionId = null)
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("a missing jti is rejected") {
            val assertion = signAssertion(key, jti = null)
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
    }

    given("timing problems") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        then("an assertion issued too far in the future is rejected") {
            val assertion = signAssertion(key, issuedAt = Date.from(Instant.now().plusSeconds(600)))
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("an assertion older than maxAssertionAgeSeconds is rejected") {
            val assertion = signAssertion(key, issuedAt = Date.from(Instant.now().minusSeconds(600)))
            shouldThrow<PeerAuthValidationException> { validator(jwkSourceReturning(key)).validate(assertion, method, url) }
        }
        then("an assertion just inside the clock-skew allowance is accepted") {
            val assertion = signAssertion(key, issuedAt = Date.from(Instant.now().plusSeconds(29)))
            validator(jwkSourceReturning(key)).validate(assertion, method, url)
        }
    }

    given("a validly signed, fresh assertion") {
        `when`("validating that same assertion a second time") {
            then("it is rejected as a replay") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val assertion = signAssertion(key)
                val v = validator(jwkSourceReturning(key))

                v.validate(assertion, method, url)

                shouldThrow<PeerAuthValidationException> { v.validate(assertion, method, url) }
            }
        }
    }
})

/** Same stub shape as [com.example.dpop.orchestrator.dpop.DpopValidatorTest]'s. */
private fun inMemoryReplayRepository(): DpopProofReplayRepository {
    val seen = mutableSetOf<String>()
    val repository = mockk<DpopProofReplayRepository>()
    every { repository.saveAndFlush(any()) } answers {
        val entry = firstArg<DpopProofReplay>()
        if (!seen.add(entry.proofKey!!)) {
            throw DataIntegrityViolationException("duplicate proof_key ${entry.proofKey}")
        }
        entry
    }
    return repository
}

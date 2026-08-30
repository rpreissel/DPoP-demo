package com.example.dpop.orchestrator.dpop

import com.nimbusds.jose.JOSEObjectType
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
 * Pure unit test of [DpopValidator] - no Spring context, no HTTP layer. Every orchestrator
 * integration test mocks `DpopValidator` outright, so this class's actual proof-checking logic
 * (RFC 9449) was otherwise never exercised at all (0% branch coverage before this file). Real
 * [JwkThumbprintService]/[DpopReplayProtectionService] instances are used rather than mocked, same
 * pattern as [DeviceProofValidatorTest].
 */
class DpopValidatorTest : BehaviorSpec({

    fun validator() = DpopValidator(
        jwkThumbprintService = JwkThumbprintService(),
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository()),
        maxClockSkewSeconds = 30,
        maxProofAgeSeconds = 120
    )

    val method = "POST"
    val url = "https://example.test/orchestrator/api/v1/channels"

    fun signProof(
        key: ECKey,
        htm: String = method,
        htu: String = url,
        issuedAt: Date = Date(),
        jti: String = UUID.randomUUID().toString(),
        nonce: String? = null,
        headerJwk: com.nimbusds.jose.jwk.JWK = key.toPublicJWK(),
        type: String = "dpop+jwt",
        algorithm: JWSAlgorithm = JWSAlgorithm.ES256
    ): String {
        val header = JWSHeader.Builder(algorithm)
            .type(JOSEObjectType(type))
            .jwk(headerJwk)
            .build()
        val claimsBuilder = JWTClaimsSet.Builder()
            .jwtID(jti)
            .issueTime(issuedAt)
            .claim("htm", htm)
            .claim("htu", htu)
        nonce?.let { claimsBuilder.claim("nonce", it) }
        val signedJWT = SignedJWT(header, claimsBuilder.build())
        signedJWT.sign(ECDSASigner(key.toECPrivateKey()))
        return signedJWT.serialize()
    }

    given("a validly signed, fresh DPoP proof") {
        `when`("validating it") {
            then("it succeeds and reports the proof's own claims") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val jti = UUID.randomUUID().toString()
                val proof = signProof(key, jti = jti, nonce = "server-nonce")

                val result = validator().validate(proof, method, url)

                result.jti shouldBe jti
                result.htm shouldBe method
                result.htu shouldBe url
                result.nonce shouldBe "server-nonce"
                JwkThumbprintService().computeThumbprint(result.publicKey) shouldBe JwkThumbprintService().computeThumbprint(key.toPublicJWK())
            }
        }

        `when`("no nonce claim was sent") {
            then("the result carries none either, rather than inventing one") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val result = validator().validate(signProof(key), method, url)
                result.nonce.shouldBeNull()
            }
        }

        `when`("the request URL carries a query string or fragment the proof's htu doesn't") {
            then("it still matches - only the URL itself is compared, not query/fragment") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val proof = signProof(key, htu = url)
                validator().validate(proof, method, "$url?foo=bar#section")
            }
        }

        `when`("validating that same proof a second time") {
            then("it is rejected as a replay") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val proof = signProof(key)
                val v = validator()

                v.validate(proof, method, url)

                shouldThrow<DpopValidationException> { v.validate(proof, method, url) }
            }
        }
    }

    given("no proof at all") {
        then("a null proof is rejected as missing") {
            shouldThrow<DpopValidationException> { validator().validate(null, method, url) }
        }
        then("a blank proof is rejected as missing") {
            shouldThrow<DpopValidationException> { validator().validate("   ", method, url) }
        }
    }

    given("a proof that isn't a well-formed JWT at all") {
        then("it is rejected as an invalid format") {
            shouldThrow<DpopValidationException> { validator().validate("not-a-jwt", method, url) }
        }
    }

    given("header problems") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        then("a missing typ header is rejected") {
            val header = JWSHeader.Builder(JWSAlgorithm.ES256).jwk(key.toPublicJWK()).build()
            val jwt = SignedJWT(header, JWTClaimsSet.Builder().jwtID("j").issueTime(Date()).claim("htm", method).claim("htu", url).build())
            jwt.sign(ECDSASigner(key.toECPrivateKey()))
            shouldThrow<DpopValidationException> { validator().validate(jwt.serialize(), method, url) }
        }

        then("the wrong typ header is rejected") {
            val proof = signProof(key, type = "JWT")
            shouldThrow<DpopValidationException> { validator().validate(proof, method, url) }
        }

        then("an unsupported algorithm is rejected") {
            val rsaKey = RSAKeyGenerator(2048).generate()
            val header = JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType("dpop+jwt")).jwk(rsaKey.toPublicJWK()).build()
            val jwt = SignedJWT(header, JWTClaimsSet.Builder().jwtID("j").issueTime(Date()).claim("htm", method).claim("htu", url).build())
            jwt.sign(RSASSASigner(rsaKey.toPrivateKey()))
            shouldThrow<DpopValidationException> { validator().validate(jwt.serialize(), method, url) }
        }

        then("a missing JWK is rejected") {
            val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("dpop+jwt")).build()
            val jwt = SignedJWT(header, JWTClaimsSet.Builder().jwtID("j").issueTime(Date()).claim("htm", method).claim("htu", url).build())
            jwt.sign(ECDSASigner(key.toECPrivateKey()))
            shouldThrow<DpopValidationException> { validator().validate(jwt.serialize(), method, url) }
        }

        // The `jwk.isPrivate` check has no reachable test here: Nimbus's own JWSHeader.Builder.jwk()
        // already rejects a private JWK before a proof could even be built with one.

        then("a header claiming an EC algorithm but carrying a non-EC JWK is rejected") {
            val rsaKey = RSAKeyGenerator(2048).generate()
            val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("dpop+jwt")).jwk(rsaKey.toPublicJWK()).build()
            val jwt = SignedJWT(header, JWTClaimsSet.Builder().jwtID("j").issueTime(Date()).claim("htm", method).claim("htu", url).build())
            jwt.sign(ECDSASigner(key.toECPrivateKey()))
            shouldThrow<DpopValidationException> { validator().validate(jwt.serialize(), method, url) }
        }
    }

    given("a proof whose header carries a different key than the one that signed it") {
        then("the signature check fails") {
            val headerKey = ECKeyGenerator(Curve.P_256).generate()
            val signingKey = ECKeyGenerator(Curve.P_256).generate()
            val proof = signProof(signingKey, headerJwk = headerKey.toPublicJWK())
            shouldThrow<DpopValidationException> { validator().validate(proof, method, url) }
        }
    }

    given("claim mismatches") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        then("a different HTTP method than the request is rejected") {
            val proof = signProof(key, htm = "GET")
            shouldThrow<DpopValidationException> { validator().validate(proof, method, url) }
        }

        then("a different URL than the request is rejected") {
            val proof = signProof(key, htu = "https://example.test/somewhere-else")
            shouldThrow<DpopValidationException> { validator().validate(proof, method, url) }
        }
    }

    given("timing problems") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        then("a proof issued too far in the future is rejected") {
            val proof = signProof(key, issuedAt = Date.from(Instant.now().plusSeconds(600)))
            shouldThrow<DpopValidationException> { validator().validate(proof, method, url) }
        }

        then("a proof older than maxProofAgeSeconds is rejected") {
            val proof = signProof(key, issuedAt = Date.from(Instant.now().minusSeconds(600)))
            shouldThrow<DpopValidationException> { validator().validate(proof, method, url) }
        }

        then("a proof just inside the clock-skew allowance is accepted") {
            val proof = signProof(key, issuedAt = Date.from(Instant.now().plusSeconds(29)))
            validator().validate(proof, method, url)
        }
    }

    given("a proof with no jti") {
        then("it is rejected") {
            val key = ECKeyGenerator(Curve.P_256).generate()
            val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("dpop+jwt")).jwk(key.toPublicJWK()).build()
            val claims = JWTClaimsSet.Builder().issueTime(Date()).claim("htm", method).claim("htu", url).build()
            val jwt = SignedJWT(header, claims)
            jwt.sign(ECDSASigner(key.toECPrivateKey()))
            shouldThrow<DpopValidationException> { validator().validate(jwt.serialize(), method, url) }
        }
    }
})

/**
 * The smallest stub that still makes replay detection real: a set plus the primary-key
 * violation. Only `saveAndFlush` is stubbed because that is the only method the service calls -
 * the check IS the insert.
 */
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

package com.example.dpop.orchestrator.dpop

import com.example.dpop.tool_api.UserVerification
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.DataIntegrityViolationException
import java.util.Date
import java.util.UUID

/**
 * Pure unit test: no Spring context, no HTTP layer. JwkThumbprintService/DpopReplayProtectionService
 * are used as real instances (fast, no external dependencies) rather than mocked, so replay
 * detection is genuinely exercised rather than assumed. The service is DB-backed now, so its
 * repository is stubbed by [inMemoryReplayRepository] - which reproduces the one behaviour the
 * detection actually rests on: a duplicate primary key raises DataIntegrityViolationException.
 */
class DeviceProofValidatorTest : BehaviorSpec({

    val validator = DeviceProofValidator(
        jwkThumbprintService = JwkThumbprintService(),
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository()),
        maxClockSkewSeconds = 30,
        maxProofAgeSeconds = 120
    )

    val url = "https://example.test/orchestrator/api/v1/tools/${UUID.randomUUID()}/enroll-device"

    fun signProof(key: ECKey, userVerification: String, issuedAt: Date = Date(), jti: String = UUID.randomUUID().toString()): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(key.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(jti)
            .issueTime(issuedAt)
            .claim("htm", "PATCH")
            .claim("htu", url)
            .claim("userVerification", userVerification)
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(key.toECPrivateKey()))
        return signedJWT.serialize()
    }

    given("a validly signed, fresh device proof") {
        `when`("validating it") {
            then("it succeeds and reports the userVerification the proof carried") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val proof = signProof(key, "biometric")

                val result = validator.validate(proof, "PATCH", url)

                result.userVerification shouldBe UserVerification.BIOMETRIC
                result.publicKey.thumbprint shouldBe JwkThumbprintService().computeThumbprint(key.toPublicJWK())
            }
        }

        `when`("validating that same proof a second time") {
            then("it is rejected as a replay") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val proof = signProof(key, "biometric")

                validator.validate(proof, "PATCH", url)

                shouldThrow<DpopValidationException> {
                    validator.validate(proof, "PATCH", url)
                }
            }
        }
    }

    given("a device proof signed more than maxProofAgeSeconds ago") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val staleProof = signProof(key, "pin", issuedAt = Date.from(java.time.Instant.now().minusSeconds(600)))

        `when`("validating it") {
            then("it is rejected as expired") {
                shouldThrow<DpopValidationException> {
                    validator.validate(staleProof, "PATCH", url)
                }
            }
        }
    }

    given("a proof whose header carries a different key than the one that signed it") {
        val headerKey = ECKeyGenerator(Curve.P_256).generate()
        val signingKey = ECKeyGenerator(Curve.P_256).generate()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(headerKey.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date())
            .claim("htm", "PATCH")
            .claim("htu", url)
            .claim("userVerification", "pin")
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(signingKey.toECPrivateKey()))
        val tamperedProof = signedJWT.serialize()

        `when`("validating it") {
            then("the signature check fails") {
                shouldThrow<DpopValidationException> {
                    validator.validate(tamperedProof, "PATCH", url)
                }
            }
        }
    }

    given("a device proof with an unsupported userVerification value") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val proof = signProof(key, "voiceprint")

        `when`("validating it") {
            then("it is rejected") {
                shouldThrow<DpopValidationException> {
                    validator.validate(proof, "PATCH", url)
                }
            }
        }
    }

    given("no device proof at all") {
        `when`("validating a null proof") {
            then("it is rejected as missing") {
                shouldThrow<DpopValidationException> {
                    validator.validate(null, "PATCH", url)
                }
            }
        }
    }
})

/**
 * The smallest stub that still makes replay detection real: a map plus the primary-key
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

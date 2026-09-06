package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Generates (once, idempotently) and reads back the per-account keypair the `keycloak` profile's
 * real token grant needs (DPoP-demo-xso). Same shape (EC P-256, nimbus JWK) as the keycloak-
 * extension's own [PeerAuthSigningKey]-style per-node key - here per-account instead, because the
 * whole point is that compromising one account's key never exposes another's.
 */
@Service
@Profile("keycloak")
@Transactional
class AccountKeypairService(
    private val repository: AccountKeycloakKeypairRepository
) {

    /** Returns the existing keypair for [accountId], generating one on first call - never regenerated afterwards. */
    fun keypairFor(accountId: Long): AccountKeycloakKeypair =
        repository.findByIdOrNull(accountId) ?: generate(accountId)

    private fun generate(accountId: Long): AccountKeycloakKeypair {
        val key = ECKeyGenerator(Curve.P_256)
            .keyID("account-$accountId")
            .algorithm(JWSAlgorithm.ES256)
            .keyUse(KeyUse.SIGNATURE)
            .generate()
        val keypair = AccountKeycloakKeypair(
            accountId = accountId,
            publicKeyJwk = key.toPublicJWK().toJSONString(),
            privateKeyJwk = key.toJSONString(),
            createdAt = Instant.now()
        )
        return repository.save(keypair)
    }
}

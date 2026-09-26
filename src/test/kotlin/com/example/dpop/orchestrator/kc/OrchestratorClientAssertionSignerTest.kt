package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/**
 * Pure unit test of [OrchestratorClientAssertionSigner] (review 2026-09, S-3): every Keycloak
 * client signs with its own key, so a forged assertion for the rights-free token client is no
 * assertion for the admin or migration client.
 */
class OrchestratorClientAssertionSignerTest : BehaviorSpec({

    fun signer(): OrchestratorClientAssertionSigner {
        val stored = mutableMapOf<String, NodeSigningKey>()
        val repository = mockk<NodeSigningKeyRepository>()
        every { repository.findById(any()) } answers { Optional.ofNullable(stored[firstArg()]) }
        every { repository.save(any<NodeSigningKey>()) } answers {
            firstArg<NodeSigningKey>().also { stored[it.purpose] = it }
        }
        return OrchestratorClientAssertionSigner(repository, "orchestrator-admin", "orchestrator-app-token")
    }

    given("the three clients this node represents") {
        val signer = signer()
        val admin = signer.publicKeyOf("orchestrator-admin")!!
        val appToken = signer.publicKeyOf("orchestrator-app-token")!!
        val migration = signer.publicKeyOf(KeycloakMigrationToken.CLIENT_ID)!!

        then("each has a key of its own") {
            admin.computeThumbprint() shouldNotBe appToken.computeThumbprint()
            admin.computeThumbprint() shouldNotBe migration.computeThumbprint()
            appToken.computeThumbprint() shouldNotBe migration.computeThumbprint()
        }

        then("an assertion verifies only against its own client's key") {
            val jwt = SignedJWT.parse(signer.assertionFor("orchestrator-app-token", "https://kc/realms/Demo"))
            jwt.verify(ECDSAVerifier(appToken)) shouldBe true
            jwt.verify(ECDSAVerifier(admin)) shouldBe false
            jwt.verify(ECDSAVerifier(migration)) shouldBe false
        }

        then("the key stays the same across calls") {
            signer.publicKeyOf("orchestrator-admin")!!.computeThumbprint() shouldBe admin.computeThumbprint()
        }
    }

    given("a client this node does not represent") {
        val signer = signer()
        then("it has no key and gets no assertion") {
            signer.publicKeyOf("some-other-client") shouldBe null
            shouldThrow<IllegalStateException> { signer.assertionFor("some-other-client", "https://kc/realms/Demo") }
        }
    }
})

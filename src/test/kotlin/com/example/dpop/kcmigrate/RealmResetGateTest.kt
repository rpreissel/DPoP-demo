package com.example.dpop.kcmigrate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RealmRepresentation

/**
 * Review 2026-09, M-10: a changed setup value (or migration) needs the realm rebuilt - which throws
 * away sessions, every `sub` and every credential on the users. Only allowed in demo mode; otherwise
 * the run stops with a message naming why, and the realm is left alone.
 */
class RealmResetGateTest : BehaviorSpec({

    val setup = RealmSetup(
        realmName = "demo", realmDisplayName = "Demo", loginTheme = "orchestrator",
        browserClientId = "web", qrTestClientId = "qr", adminApiClientId = "admin", appTokenClientId = "app",
        browserRedirectUris = listOf("http://localhost/*"), orchestratorBaseUrl = "http://orchestrator:8080",
        publicOrchestratorBaseUrl = "http://localhost:8080", peerAuthIssuer = "kc", peerAuthAudience = "orch"
    )

    fun keycloakWithStoredSetup(): Pair<Keycloak, RealmResource> {
        val realm = mockk<RealmResource>(relaxed = true)
        // The realm was built with a DIFFERENT orchestratorBaseUrl than the one configured now.
        every { realm.toRepresentation() } returns RealmRepresentation().apply {
            setRealm("demo")
            attributes = mutableMapOf("kcmig_setup__orchestratorBaseUrl" to "http://old-host:8080")
        }
        val kc = mockk<Keycloak>()
        every { kc.realm("demo") } returns realm
        return kc to realm
    }

    given("a setup value that changed since the realm was built") {
        `when`("the rebuild is not allowed (demo.mode=false)") {
            then("the run stops with the reason, and the realm is not removed") {
                val (kc, realm) = keycloakWithStoredSetup()
                val refused = shouldThrow<RealmResetRefusedException> {
                    MigrationRunner(kc, setup, emptyList(), allowRealmReset = false).up()
                }
                refused.message shouldContain "orchestratorBaseUrl"
                verify(exactly = 0) { realm.remove() }
            }
        }

        `when`("the rebuild is allowed (demo mode)") {
            then("the realm is removed and rebuilt, as before") {
                val (kc, realm) = keycloakWithStoredSetup()
                runCatching { MigrationRunner(kc, setup, emptyList(), allowRealmReset = true).up() }
                verify(atLeast = 1) { realm.remove() }
            }
        }
    }
})

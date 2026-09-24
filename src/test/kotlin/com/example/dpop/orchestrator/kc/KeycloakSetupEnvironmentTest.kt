package com.example.dpop.orchestrator.kc

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

/**
 * [KeycloakSetupEnvironment] ist die Stelle, an der aus dem gewaehlten Parametersatz die
 * Laufzeitwerte des Orchestrators werden. Faellt sie aus, fehlen Properties, auf die andere Beans
 * per `@Value` bestehen - der Start scheitert dann mit einem unverstaendlichen
 * "Could not resolve placeholder", weit weg von der Ursache. Diese Tests halten beides fest: dass
 * die Ableitung stimmt, und dass sie ueberhaupt registriert ist.
 */
class KeycloakSetupEnvironmentTest {

    private fun environmentWith(variant: String): MockEnvironment {
        val env = MockEnvironment()
        env.setActiveProfiles("keycloak")
        env.propertySources.addFirst(
            MapPropertySource(
                "test-setup",
                mapOf(
                    "keycloak-setup.variant" to variant,
                    "keycloak-setup.base.realmName" to "Demo",
                    "keycloak-setup.base.realmDisplayName" to "Demo",
                    "keycloak-setup.base.loginTheme" to "orchestrator",
                    "keycloak-setup.base.browserClientId" to "dpop-demo-web",
                    "keycloak-setup.base.qrTestClientId" to "dpop-demo-web-qr-test",
                    "keycloak-setup.base.adminApiClientId" to "orchestrator-admin",
                    "keycloak-setup.base.appTokenClientId" to "orchestrator-app-token",
                    "keycloak-setup.base.browserRedirectUris" to "http://localhost:8080/*",
                    "keycloak-setup.base.orchestratorBaseUrl" to "http://localhost:8080",
                    "keycloak-setup.base.publicOrchestratorBaseUrl" to "http://localhost:8080",
                    "keycloak-setup.base.peerAuthIssuer" to "dpop-demo-keycloak",
                    "keycloak-setup.base.peerAuthAudience" to "dpop-demo-orchestrator",
                    "keycloak-setup.base.keycloakBaseUrl" to "https://localhost:8543",
                    "keycloak-setup.base.publicKeycloakBaseUrl" to "https://localhost:8543",
                    "keycloak-setup.variants.compose.keycloakBaseUrl" to "https://keycloak:8443",
                    "keycloak-setup.variants.compose.orchestratorBaseUrl" to "http://orchestrator:8080",
                ),
            ),
        )
        return env
    }

    @Test
    fun `leitet Sync-, Peer-Auth- und OIDC-Werte aus der gewaehlten Variante ab`() {
        val env = environmentWith("compose")

        KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication())

        // Die Variante gewinnt ueber die Basis - und zwar ueberall dort, wo der Wert einfliesst.
        assertThat(env.getProperty("keycloak-sync.base-url")).isEqualTo("https://keycloak:8443")
        assertThat(env.getProperty("keycloak-migrate.base-url")).isEqualTo("https://keycloak:8443")
        assertThat(env.getProperty("keycloak-migrate.admin-username")).isNull()
        assertThat(env.getProperty("keycloak-sync.realm")).isEqualTo("Demo")
        assertThat(env.getProperty("keycloak-sync.admin-client-id")).isEqualTo("orchestrator-admin")
        assertThat(env.getProperty("keycloak-sync.app-client-id")).isEqualTo("orchestrator-app-token")
        // Der Browser bekommt die oeffentliche Adresse, nicht die der Variante fuer Server-zu-Server.
        assertThat(env.getProperty("keycloak-sync.public-base-url")).isEqualTo("https://localhost:8543")
        assertThat(env.getProperty("keycloak-web.browser-client-id")).isEqualTo("dpop-demo-web")
        assertThat(env.getProperty("keycloak-web.qr-test-client-id")).isEqualTo("dpop-demo-web-qr-test")
        assertThat(env.getProperty("kc.peer-auth.jwks-uri"))
            .isEqualTo("https://keycloak:8443/realms/Demo/orchestrator-jwks/.well-known/jwks.json")
        assertThat(env.getProperty("kc.peer-auth.issuer")).isEqualTo("dpop-demo-keycloak")
        assertThat(env.getProperty("kc.peer-auth.audience")).isEqualTo("dpop-demo-orchestrator")
    }

    @Test
    fun `haelt sich heraus, solange das keycloak-Profil nicht aktiv ist`() {
        val env = environmentWith("compose")
        env.setActiveProfiles("default")

        KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication())

        // Das Default-Profil bringt eigene kc.*-Werte mit (leerer Aussteller) - die duerfen hier nicht
        // ueberschrieben werden.
        assertThat(env.getProperty("keycloak-sync.base-url")).isNull()
    }

    @Test
    fun `nennt eine unbekannte Variante beim Namen, statt still nichts zu tun`() {
        val env = environmentWith("gibtsnicht")

        assertThatThrownBy { KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication()) }
            .hasMessageContaining("gibtsnicht")
            .hasMessageContaining("compose")
    }

    /**
     * Ohne diesen Eintrag laedt Spring den Post-Processor nicht, und der Start scheitert erst
     * spaeter an einem fehlenden Platzhalter. `.imports`-Dateien helfen hier NICHT - die gelten
     * nur fuer Auto-Konfigurationen.
     */
    @Test
    fun `ist in spring-factories registriert`() {
        val registration = ClassPathResource("META-INF/spring.factories")
            .inputStream.bufferedReader().readText()

        assertThat(registration)
            .contains("org.springframework.boot.EnvironmentPostProcessor")
            .contains(KeycloakSetupEnvironment::class.java.name)
    }
}

package com.example.dpop.orchestrator.kc

import jakarta.annotation.PostConstruct
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Trusts the real Keycloak's self-signed dev certificate (`compose.yml`'s `KC_HTTPS_KEY_STORE_*`,
 * generated fresh at image build time - see `keycloak-extension/Dockerfile`) for the two
 * server-to-server HTTPS calls this backend itself makes to it: [KeycloakJwkSource]'s JWKS fetch
 * and [KeycloakAdminClient]'s Admin REST calls. HTTP is disabled entirely on the Keycloak side
 * (`KC_HTTP_ENABLED=false`), so there is no plaintext fallback to prefer instead.
 *
 * Demo-only, same spirit as `tls_insecure_skip_verify` in the tofu provider config and `curl -k`
 * throughout this stack's own tooling: a real deployment would import the actual CA into the JVM's
 * trust store rather than disabling verification JVM-wide. Installed as the JVM DEFAULT
 * SSLContext/HostnameVerifier because both call sites go through library code
 * ([com.nimbusds.jose.jwk.JWKSet.load], Spring's `RestClient` default `java.net.http.HttpClient`)
 * that builds its own HTTP client internally rather than accepting an injected one - only wired up
 * under the `keycloak` Spring profile, so the default (Mock-Keycloak) profile's own TLS behavior is
 * completely untouched.
 */
@Component
@Profile("keycloak")
class KeycloakTlsConfig {

    @PostConstruct
    fun installTrustAllForLocalKeycloak() {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        SSLContext.setDefault(sslContext)
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(HostnameVerifier { _, _ -> true })
    }
}

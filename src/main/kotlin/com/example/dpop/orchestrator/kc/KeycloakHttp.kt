package com.example.dpop.orchestrator.kc

import java.net.HttpURLConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * The one place the orchestrator's HTTP clients TOWARD KEYCLOAK come from: Admin API, account
 * sync, migration token, migration probe and the peer-auth JWKS fetch.
 *
 * Whether Keycloak's certificate is checked is a property of the chosen setup variant
 * (`keycloak-setup ... trustSelfSignedCertificate`), not of the Spring profile: the local
 * variants run Keycloak with a self-signed `start-dev` certificate that only names `localhost`,
 * everything else checks certificates normally. And the exception is scoped to the clients built
 * here - it used to be installed as the JVM-wide default `SSLContext` and hostname verifier, which
 * silently disabled certificate checks for EVERY outgoing HTTPS call of the process, including
 * future real providers (review 2026-09, S-4).
 */
@Component
@Profile("keycloak")
class KeycloakHttp(
    @Value("\${keycloak-tls.trust-self-signed}") val trustSelfSigned: Boolean,
) {
    init {
        if (trustSelfSigned) {
            log.warn(
                "Keycloak certificate is NOT verified (keycloak-setup trustSelfSignedCertificate=true) - " +
                    "only for Keycloak connections, only for a self-signed development certificate"
            )
        }
    }

    private val requestFactory = if (trustSelfSigned) TrustingRequestFactory(trustAllSocketFactory()) else SimpleClientHttpRequestFactory()

    /** A [RestClient] against [baseUrl], with this setup's certificate policy. */
    fun restClient(baseUrl: String): RestClient =
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build()

    /** GETs [url] as text, with this setup's certificate policy - for the JWKS fetch. */
    fun getText(url: String): String =
        RestClient.builder().requestFactory(requestFactory).build()
            .get().uri(url).retrieve().body<String>()
            ?: error("Empty response from $url")

    /** Per-connection trust exception - never touches the JVM defaults. */
    private class TrustingRequestFactory(private val socketFactory: SSLSocketFactory) : SimpleClientHttpRequestFactory() {
        override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = socketFactory
                connection.setHostnameVerifier { _, _ -> true }
            }
            super.prepareConnection(connection, httpMethod)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(KeycloakHttp::class.java)

        fun trustAllSocketFactory(): SSLSocketFactory {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
                .socketFactory
        }
    }
}

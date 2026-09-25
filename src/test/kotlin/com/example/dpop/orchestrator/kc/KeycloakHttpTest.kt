package com.example.dpop.orchestrator.kc

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * Regression guard for review 2026-09, S-4: trusting Keycloak's self-signed certificate must stay
 * an exception of the Keycloak clients built by [KeycloakHttp] - it used to replace the JVM-wide
 * default SSLContext and hostname verifier, disabling certificate checks for every outgoing call.
 */
class KeycloakHttpTest : BehaviorSpec({

    given("a setup that trusts Keycloak's self-signed certificate") {
        val defaultContext = SSLContext.getDefault()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        val http = KeycloakHttp(trustSelfSigned = true)
        http.restClient("https://localhost:8543")

        then("the JVM defaults are untouched") {
            SSLContext.getDefault() shouldBeSameInstanceAs defaultContext
            HttpsURLConnection.getDefaultHostnameVerifier() shouldBeSameInstanceAs defaultVerifier
            HttpsURLConnection.getDefaultSSLSocketFactory() shouldBeSameInstanceAs defaultSocketFactory
            System.getProperty("jdk.internal.httpclient.disableHostnameVerification") shouldBe null
        }
    }
})

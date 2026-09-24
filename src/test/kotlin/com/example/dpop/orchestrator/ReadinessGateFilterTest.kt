package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.kc.CLIENT_JWKS_PATH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Waehrend der Keycloak-Migrationen blockt das Gate alles - bis auf das Client-JWKS: gegen genau
 * dieses prueft Keycloak die Anmeldung der Migration selbst. Stand es hinter dem Gate, bekam
 * Keycloak ein 503, lehnte die Assertion ab ("Error when loading public keys"), und der
 * Orchestrator startete nie.
 */
class ReadinessGateFilterTest {

    private val starting = object : ReadinessState {
        override val isReady = false
    }

    private fun call(uri: String): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        ReadinessGateFilter(starting).doFilter(MockHttpServletRequest("GET", uri), response, MockFilterChain())
        return response
    }

    @Test
    fun `blockt waehrend der Migration normale Requests mit 503`() {
        assertThat(call("/orchestrator/api/v1/app/channels").status).isEqualTo(503)
    }

    @Test
    fun `laesst das Client-JWKS schon waehrend der Migration durch`() {
        assertThat(call("$CLIENT_JWKS_PATH/.well-known/jwks.json").status).isEqualTo(200)
    }

    @Test
    fun `nimmt nur genau diesen Pfad aus, keinen gleich beginnenden`() {
        assertThat(call("${CLIENT_JWKS_PATH}-andere/x").status).isEqualTo(503)
    }
}

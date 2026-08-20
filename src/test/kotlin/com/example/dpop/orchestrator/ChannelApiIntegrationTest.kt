package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.nimbusds.jose.jwk.JWK
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChannelApiIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var dpopValidator: DpopValidator

    @MockitoBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    private val restTemplate = RestTemplate()

    private fun mockDpop(bindingKeyRef: String) {
        val fakeJwk = mock(JWK::class.java)
        val fakeProof = DpopProof(
            token = "mock-token",
            publicKey = fakeJwk,
            jti = "mock-jti",
            htm = "POST",
            htu = "http://localhost/mock",
            issuedAt = Instant.now(),
            nonce = null
        )
        `when`(dpopValidator.validate(anyString(), anyString(), anyString())).thenReturn(fakeProof)
        `when`(jwkThumbprintService.computeThumbprint(fakeJwk)).thenReturn(bindingKeyRef)
    }

    private fun dpopHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.set("DPoP", "mock-dpop-token")
        headers.set("Content-Type", "application/json")
        return headers
    }

    @Test
    fun createChannel_returnsChannelSessionId() {
        mockDpop("binding-key-1")

        val response = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels",
            HttpMethod.POST,
            HttpEntity("{}", dpopHeaders()),
            Map::class.java as Class<Map<String, Any>>
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).containsKey("channelSessionId")
    }

    @Test
    fun startFscAttempt_withEmptyBody_returns201InputRequired() {
        mockDpop("binding-key-2")

        val channelResponse = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels",
            HttpMethod.POST,
            HttpEntity("{}", dpopHeaders()),
            Map::class.java as Class<Map<String, Any>>
        )
        assertThat(channelResponse.statusCode).isEqualTo(HttpStatus.OK)
        val channelSessionId = channelResponse.body!!["channelSessionId"].toString()

        val fscResponse = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels/$channelSessionId/identification-methods/fsc/attempts",
            HttpMethod.POST,
            HttpEntity("{}", dpopHeaders()),
            Map::class.java as Class<Map<String, Any>>
        )

        assertThat(fscResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val attemptState = fscResponse.body!!["attemptState"] as Map<*, *>
        assertThat(attemptState["status"]).isEqualTo("INPUT_REQUIRED")
        assertThat(attemptState["missingFields"].toString()).contains("kvnr")
        assertThat(attemptState["missingFields"].toString()).contains("fsc")
    }

    @Test
    fun startFscAttempt_withKvnrOnly_returnsMissingFscOnly() {
        mockDpop("binding-key-3")

        val channelResponse = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels",
            HttpMethod.POST,
            HttpEntity("{}", dpopHeaders()),
            Map::class.java as Class<Map<String, Any>>
        )
        val channelSessionId = channelResponse.body!!["channelSessionId"].toString()

        val fscResponse = restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/app/channels/$channelSessionId/identification-methods/fsc/attempts",
            HttpMethod.POST,
            HttpEntity("{\"kvnr\":\"A123456789\"}", dpopHeaders()),
            Map::class.java as Class<Map<String, Any>>
        )

        assertThat(fscResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val attemptState = fscResponse.body!!["attemptState"] as Map<*, *>
        assertThat(attemptState["status"]).isEqualTo("INPUT_REQUIRED")
        assertThat(attemptState["missingFields"].toString()).doesNotContain("kvnr")
        assertThat(attemptState["missingFields"].toString()).contains("fsc")
    }
}
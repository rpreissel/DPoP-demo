package com.example.dpop.orchestrator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProcessControllerIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = RestTemplate()

    @Test
    fun processEndpointReturnsOrchestratorResult() {
        val body = restTemplate.getForObject(
            "http://localhost:$port/orchestrator/process",
            String::class.java
        )

        assertThat(body).contains("id_fsc", "account", "ext_stammdaten")
    }
}
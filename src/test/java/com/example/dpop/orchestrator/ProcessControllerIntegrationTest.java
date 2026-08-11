package com.example.dpop.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProcessControllerIntegrationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void processEndpointReturnsOrchestratorResult() {
        String body = restTemplate.getForObject(
                "http://localhost:" + port + "/orchestrator/process",
                String.class);

        assertThat(body).contains("id_fsc", "+49170", "account", "ext_stammdaten");
    }
}

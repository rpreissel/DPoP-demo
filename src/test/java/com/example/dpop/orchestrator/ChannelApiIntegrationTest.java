package com.example.dpop.orchestrator;

import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChannelApiIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private DpopValidator dpopValidator;

    @MockitoBean
    private JwkThumbprintService jwkThumbprintService;

    private final RestTemplate restTemplate = new RestTemplate();

    private void mockDpop(String bindingKeyRef) throws Exception {
        DpopProof fakeProof = mock(DpopProof.class);
        when(dpopValidator.validate(anyString(), anyString(), anyString())).thenReturn(fakeProof);
        when(jwkThumbprintService.computeThumbprint(any())).thenReturn(bindingKeyRef);
    }

    private HttpHeaders dpopHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("DPoP", "mock-dpop-token");
        headers.set("Content-Type", "application/json");
        return headers;
    }

    @Test
    void createChannel_returnsChannelSessionId() throws Exception {
        mockDpop("binding-key-1");

        ResponseEntity<Map> response = restTemplate.exchange(
                "http://localhost:" + port + "/orchestrator/api/v1/app/channels",
                HttpMethod.POST,
                new HttpEntity<>("{}", dpopHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("channelSessionId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startFscAttempt_withEmptyBody_returns201InputRequired() throws Exception {
        mockDpop("binding-key-2");

        ResponseEntity<Map> channelResponse = restTemplate.exchange(
                "http://localhost:" + port + "/orchestrator/api/v1/app/channels",
                HttpMethod.POST,
                new HttpEntity<>("{}", dpopHeaders()),
                Map.class
        );
        assertThat(channelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String channelSessionId = channelResponse.getBody().get("channelSessionId").toString();

        ResponseEntity<Map> fscResponse = restTemplate.exchange(
                "http://localhost:" + port + "/orchestrator/api/v1/app/channels/" + channelSessionId
                        + "/identification-methods/fsc/attempts",
                HttpMethod.POST,
                new HttpEntity<>("{}", dpopHeaders()),
                Map.class
        );

        assertThat(fscResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> attemptState = (Map<?, ?>) fscResponse.getBody().get("attemptState");
        assertThat(attemptState).isNotNull();
        assertThat(attemptState.get("status")).isEqualTo("INPUT_REQUIRED");
        assertThat(attemptState.get("missingFields").toString()).contains("kvnr");
        assertThat(attemptState.get("missingFields").toString()).contains("fsc");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startFscAttempt_withKvnrOnly_returnsMissingFscOnly() throws Exception {
        mockDpop("binding-key-3");

        ResponseEntity<Map> channelResponse = restTemplate.exchange(
                "http://localhost:" + port + "/orchestrator/api/v1/app/channels",
                HttpMethod.POST,
                new HttpEntity<>("{}", dpopHeaders()),
                Map.class
        );
        String channelSessionId = channelResponse.getBody().get("channelSessionId").toString();

        ResponseEntity<Map> fscResponse = restTemplate.exchange(
                "http://localhost:" + port + "/orchestrator/api/v1/app/channels/" + channelSessionId
                        + "/identification-methods/fsc/attempts",
                HttpMethod.POST,
                new HttpEntity<>("{\"kvnr\":\"A123456789\"}", dpopHeaders()),
                Map.class
        );

        assertThat(fscResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> attemptState = (Map<?, ?>) fscResponse.getBody().get("attemptState");
        assertThat(attemptState.get("status")).isEqualTo("INPUT_REQUIRED");
        assertThat(attemptState.get("missingFields").toString()).doesNotContain("kvnr");
        assertThat(attemptState.get("missingFields").toString()).contains("fsc");
    }
}

package com.example.dpop.orchestrator.session;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionControllerIntegrationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void sessionFlowReturnsRegistrationStepAndCreatesSession() throws Exception {
        ECKey ecKey = new ECKeyGenerator(Curve.P_256).generate();

        String sessionsUrl = "http://localhost:" + port + "/orchestrator/sessions";
        String sessionsProof = createDpopProof(ecKey, "GET", sessionsUrl);

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("DPoP", sessionsProof);
        ResponseEntity<SessionStatusResponse> statusResponse = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                SessionStatusResponse.class);

        assertThat(statusResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().nextStep()).isNotNull();
        assertThat(statusResponse.getBody().nextStep().type()).isEqualTo("registration");
        assertThat(statusResponse.getBody().nextStep().identificationMethods()).isNull();

        String setupUrl = "http://localhost:" + port + "/orchestrator/registration-sessions";
        String setupProof = createDpopProof(ecKey, "POST", setupUrl);

        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.set("DPoP", setupProof);
        ResponseEntity<Map> setupResponse = restTemplate.exchange(
                setupUrl,
                HttpMethod.POST,
                new HttpEntity<>(setupHeaders),
                Map.class);

        assertThat(setupResponse.getStatusCode().value()).isEqualTo(200);
        String sessionId = (String) setupResponse.getBody().get("registrationSessionId");
        assertThat(sessionId).isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> nextStep = (Map<String, Object>) setupResponse.getBody().get("nextStep");
        assertThat(nextStep).isNotNull();
        assertThat(nextStep.get("type")).isEqualTo("useIdentificationMethod");
        @SuppressWarnings("unchecked")
        java.util.List<String> identificationMethods = (java.util.List<String>) nextStep.get("identificationMethods");
        assertThat(identificationMethods).containsExactly("fsc");

        String stepUrl = setupUrl + "/" + sessionId + "/steps";
        String stepProof = createDpopProof(ecKey, "POST", stepUrl);

        HttpHeaders stepHeaders = new HttpHeaders();
        stepHeaders.set("DPoP", stepProof);
        ResponseEntity<Map> stepResponse = restTemplate.exchange(
                stepUrl,
                HttpMethod.POST,
                new HttpEntity<>(stepHeaders),
                Map.class);

        assertThat(stepResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(stepResponse.getBody().get("status")).isEqualTo("ok");

        String sessionsProof2 = createDpopProof(ecKey, "GET", sessionsUrl);
        HttpHeaders getHeaders2 = new HttpHeaders();
        getHeaders2.set("DPoP", sessionsProof2);
        ResponseEntity<SessionStatusResponse> statusResponse2 = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders2),
                SessionStatusResponse.class);

        assertThat(statusResponse2.getBody().registrationSessionId()).isEqualTo(UUID.fromString(sessionId));
        assertThat(statusResponse2.getBody().nextStep()).isNull();
    }

    private String createDpopProof(ECKey ecKey, String method, String url) throws Exception {
        JWK publicJwk = ecKey.toPublicJWK();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new com.nimbusds.jose.JOSEObjectType("dpop+jwt"))
                .jwk(publicJwk)
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now().truncatedTo(ChronoUnit.SECONDS)))
                .claim("htm", method)
                .claim("htu", url)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new ECDSASigner(ecKey));
        return signedJWT.serialize();
    }
}

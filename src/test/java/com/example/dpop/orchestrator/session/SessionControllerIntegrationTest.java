package com.example.dpop.orchestrator.session;

import com.example.dpop.ext_stammdaten.Person;
import com.example.dpop.ext_stammdaten.PersonRepository;
import com.example.dpop.id_fsc.FscCode;
import com.example.dpop.id_fsc.FscCodeRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private FscCodeRepository fscCodeRepository;

    private final RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));

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
        assertThat(statusResponse.getBody().next()).isNotNull();
        assertThat(statusResponse.getBody().next().context()).isEqualTo("registration");
        assertThat(statusResponse.getBody().next().step()).isEqualTo("registration");

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
        Map<String, Object> next = (Map<String, Object>) setupResponse.getBody().get("next");
        assertThat(next).isNotNull();
        assertThat(next.get("context")).isEqualTo("registration");
        assertThat(next.get("step")).isEqualTo("useIdentificationMethod");
        @SuppressWarnings("unchecked")
        List<String> identificationMethods = (List<String>) next.get("identificationMethods");
        assertThat(identificationMethods).containsExactly("fsc");

        Person person = personRepository.findByKvnr("A123456789").orElseThrow();
        fscCodeRepository.save(new FscCode(person.getId(), "VALIDCODE", Instant.now().plus(1, ChronoUnit.HOURS)));

        String identificationUrl = setupUrl + "/" + sessionId + "/identification-methods/fsc";
        String identificationProof = createDpopProof(ecKey, "POST", identificationUrl);

        HttpHeaders identificationHeaders = new HttpHeaders();
        identificationHeaders.set("DPoP", identificationProof);
        identificationHeaders.set("Content-Type", "application/json");
        HttpEntity<Map<String, String>> identificationEntity = new HttpEntity<>(Map.of(
                "kvnr", "A123456789",
                "name", "Muster",
                "vorname", "Max"
        ), identificationHeaders);
        ResponseEntity<Map> identificationResponse = restTemplate.exchange(
                identificationUrl,
                HttpMethod.POST,
                identificationEntity,
                Map.class);

        assertThat(identificationResponse.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> identificationNext = (Map<String, Object>) identificationResponse.getBody().get("next");
        assertThat(identificationNext.get("context")).isEqualTo("fsc");
        assertThat(identificationNext.get("step")).isEqualTo("input");

        String fscUrl = setupUrl + "/" + sessionId + "/identification-methods/fsc";
        String fscProof = createDpopProof(ecKey, "PATCH", fscUrl);

        HttpHeaders fscHeaders = new HttpHeaders();
        fscHeaders.set("DPoP", fscProof);
        fscHeaders.set("Content-Type", "application/json");
        HttpEntity<Map<String, String>> fscEntity = new HttpEntity<>(Map.of("fsc", "VALIDCODE"), fscHeaders);
        ResponseEntity<Map> fscResponse = restTemplate.exchange(
                fscUrl,
                HttpMethod.PATCH,
                fscEntity,
                Map.class);

        assertThat(fscResponse.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> fscNext = (Map<String, Object>) fscResponse.getBody().get("next");
        assertThat(fscNext.get("context")).isEqualTo("authentication");
        assertThat(fscNext.get("step")).isEqualTo("setup");
        @SuppressWarnings("unchecked")
        List<String> authenticationMethods = (List<String>) fscNext.get("authenticationMethods");
        assertThat(authenticationMethods).containsExactly("sms");

        String sessionsProof2 = createDpopProof(ecKey, "GET", sessionsUrl);
        HttpHeaders getHeaders2 = new HttpHeaders();
        getHeaders2.set("DPoP", sessionsProof2);
        ResponseEntity<SessionStatusResponse> statusResponse2 = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders2),
                SessionStatusResponse.class);

        assertThat(statusResponse2.getBody().registrationSessionId()).isEqualTo(UUID.fromString(sessionId));
        assertThat(statusResponse2.getBody().next()).isNull();
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

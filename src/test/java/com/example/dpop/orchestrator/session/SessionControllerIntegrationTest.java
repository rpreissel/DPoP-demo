package com.example.dpop.orchestrator.session;

import com.example.dpop.account.Account;
import com.example.dpop.account.AccountRepository;
import com.example.dpop.auth_sms.AuthSmsSetup;
import com.example.dpop.auth_sms.AuthSmsSetupRepository;
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
import org.springframework.web.client.HttpClientErrorException;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private FscCodeRepository fscCodeRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuthSmsSetupRepository authSmsSetupRepository;

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

        String setupUrl = "http://localhost:" + port + "/orchestrator/sessions";
        String setupProof = createDpopProof(ecKey, "POST", setupUrl);

        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.set("DPoP", setupProof);
        ResponseEntity<Map> setupResponse = restTemplate.exchange(
                setupUrl,
                HttpMethod.POST,
                new HttpEntity<>(setupHeaders),
                Map.class);

        assertThat(setupResponse.getStatusCode().value()).isEqualTo(200);
        String sessionId = (String) setupResponse.getBody().get("sessionId");
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
        fscCodeRepository.save(new FscCode(person.getId(), "TESTCODE123", Instant.now().plus(1, ChronoUnit.HOURS)));

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
        HttpEntity<Map<String, String>> fscEntity = new HttpEntity<>(Map.of("fsc", "TESTCODE123"), fscHeaders);
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

        String smsSetupUrl = setupUrl + "/" + sessionId + "/authentication-methods/sms";
        String smsSetupProof = createDpopProof(ecKey, "POST", smsSetupUrl);

        HttpHeaders smsSetupHeaders = new HttpHeaders();
        smsSetupHeaders.set("DPoP", smsSetupProof);
        smsSetupHeaders.set("Content-Type", "application/json");
        HttpEntity<Map<String, String>> smsSetupEntity = new HttpEntity<>(Map.of("phoneNumber", "+49 170 1234567"), smsSetupHeaders);
        ResponseEntity<Map> smsSetupResponse = restTemplate.exchange(
                smsSetupUrl,
                HttpMethod.POST,
                smsSetupEntity,
                Map.class);

        assertThat(smsSetupResponse.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> smsSetupNext = (Map<String, Object>) smsSetupResponse.getBody().get("next");
        assertThat(smsSetupNext.get("context")).isEqualTo("authentication");
        assertThat(smsSetupNext.get("step")).isEqualTo("smsTanInput");
        Number smsSetupIdNumber = (Number) smsSetupNext.get("smsSetupId");
        Long smsSetupId = smsSetupIdNumber.longValue();

        AuthSmsSetup setup = authSmsSetupRepository.findById(smsSetupId).orElseThrow();
        assertThat(setup.getPhoneNumber()).isEqualTo("+491701234567");
        assertThat(setup.isValidated()).isFalse();

        String smsTanUrl = setupUrl + "/" + sessionId + "/authentication-methods/sms/verify";
        String smsTanProof = createDpopProof(ecKey, "POST", smsTanUrl);

        HttpHeaders smsTanHeaders = new HttpHeaders();
        smsTanHeaders.set("DPoP", smsTanProof);
        smsTanHeaders.set("Content-Type", "application/json");
        HttpEntity<Map<String, Object>> smsTanEntity = new HttpEntity<>(Map.of(
                "smsSetupId", smsSetupId,
                "tan", setup.getTan()
        ), smsTanHeaders);
        ResponseEntity<Map> smsTanResponse = restTemplate.exchange(
                smsTanUrl,
                HttpMethod.POST,
                smsTanEntity,
                Map.class);

        assertThat(smsTanResponse.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> smsTanNext = (Map<String, Object>) smsTanResponse.getBody().get("next");
        String authorisationSessionId = (String) smsTanResponse.getBody().get("sessionId");
        assertThat(authorisationSessionId).isNotBlank();
        assertThat(smsTanNext.get("context")).isEqualTo("authentication");
        assertThat(smsTanNext.get("step")).isEqualTo("authenticated");

        AuthSmsSetup validatedSetup = authSmsSetupRepository.findById(smsSetupId).orElseThrow();
        assertThat(validatedSetup.isValidated()).isTrue();

        Person identifiedPerson = personRepository.findByKvnr("A123456789").orElseThrow();
        Account account = accountRepository.findByPersonId(identifiedPerson.getId()).orElseThrow();
        assertThat(account.getPersonId()).isEqualTo(identifiedPerson.getId());
        assertThat(account.getIdentifications()).hasSize(1);
        assertThat(account.getIdentifications().get(0).getIdentificationMethod()).isEqualTo("fsc");
        assertThat(account.getIdentifications().get(0).getIdentificationQuality()).isEqualTo("HIGH");
        assertThat(account.getIdentifications().get(0).getIdentifiedAt()).isNotNull();
        assertThat(account.getAuthenticationMethods()).hasSize(1);
        assertThat(account.getAuthenticationMethods().get(0).getMethod()).isEqualTo("sms");
        assertThat(account.getAuthenticationMethods().get(0).isActive()).isTrue();
        assertThat(account.getAuthenticationMethods().get(0).getDetails()).containsKey("smsSetupId");

        String sessionsProof2 = createDpopProof(ecKey, "GET", sessionsUrl);
        HttpHeaders getHeaders2 = new HttpHeaders();
        getHeaders2.set("DPoP", sessionsProof2);
        ResponseEntity<SessionStatusResponse> statusResponse2 = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(getHeaders2),
                SessionStatusResponse.class);

        assertThat(statusResponse2.getBody().sessionId()).isNotNull();
        assertThat(statusResponse2.getBody().sessionId()).isNotEqualTo(UUID.fromString(authorisationSessionId));
        assertThat(statusResponse2.getBody().next()).isNotNull();
        assertThat(statusResponse2.getBody().next().step()).isEqualTo("selectMethod");

        UUID rotatedSessionId = statusResponse2.getBody().sessionId();
        String challengeUrl2 = "http://localhost:" + port + "/orchestrator/sessions/" + rotatedSessionId + "/authentication-methods/sms";
        String challengeProof2 = createDpopProof(ecKey, "POST", challengeUrl2);
        HttpHeaders challengeHeaders2 = new HttpHeaders();
        challengeHeaders2.set("DPoP", challengeProof2);
        challengeHeaders2.set("Content-Type", "application/json");
        ResponseEntity<Map> challengeResponse2 = restTemplate.exchange(
                challengeUrl2,
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), challengeHeaders2),
                Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> challengeNext2 = (Map<String, Object>) challengeResponse2.getBody().get("next");
        assertThat(challengeNext2.get("step")).isEqualTo("smsTanInput");
    }

    @Test
    void reusesExistingAccountAndSwitchesToAuthorisationSessionAfterFsc() throws Exception {
        ECKey firstKey = new ECKeyGenerator(Curve.P_256).generate();
        String sessionsUrl = "http://localhost:" + port + "/orchestrator/sessions";
        String setupUrl = "http://localhost:" + port + "/orchestrator/sessions";

        Person person = personRepository.findByKvnr("C111111111").orElseThrow();
        fscCodeRepository.save(new FscCode(person.getId(), "REUSE123", Instant.now().plus(1, ChronoUnit.HOURS)));

        String firstSetupProof = createDpopProof(firstKey, "POST", setupUrl);
        HttpHeaders firstSetupHeaders = new HttpHeaders();
        firstSetupHeaders.set("DPoP", firstSetupProof);
        ResponseEntity<Map> firstSetupResponse = restTemplate.exchange(
                setupUrl,
                HttpMethod.POST,
                new HttpEntity<>(firstSetupHeaders),
                Map.class);

        String firstRegistrationSessionId = (String) firstSetupResponse.getBody().get("sessionId");
        assertThat(firstRegistrationSessionId).isNotBlank();

        String firstIdentificationUrl = setupUrl + "/" + firstRegistrationSessionId + "/identification-methods/fsc";
        String firstIdentificationProof = createDpopProof(firstKey, "POST", firstIdentificationUrl);
        HttpHeaders firstIdentificationHeaders = new HttpHeaders();
        firstIdentificationHeaders.set("DPoP", firstIdentificationProof);
        firstIdentificationHeaders.set("Content-Type", "application/json");
        restTemplate.exchange(
                firstIdentificationUrl,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("kvnr", "C111111111", "name", "Doe", "vorname", "Jane"), firstIdentificationHeaders),
                Map.class);

        String firstFscProof = createDpopProof(firstKey, "PATCH", firstIdentificationUrl);
        HttpHeaders firstFscHeaders = new HttpHeaders();
        firstFscHeaders.set("DPoP", firstFscProof);
        firstFscHeaders.set("Content-Type", "application/json");
        ResponseEntity<Map> firstFscResponse = restTemplate.exchange(
                firstIdentificationUrl,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("fsc", "REUSE123"), firstFscHeaders),
                Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstFscNext = (Map<String, Object>) firstFscResponse.getBody().get("next");
        assertThat(firstFscNext.get("step")).isIn("setup", "selectMethod");

        if ("setup".equals(firstFscNext.get("step"))) {
            String firstSmsSetupUrl = setupUrl + "/" + firstRegistrationSessionId + "/authentication-methods/sms";
            String firstSmsSetupProof = createDpopProof(firstKey, "POST", firstSmsSetupUrl);
            HttpHeaders firstSmsSetupHeaders = new HttpHeaders();
            firstSmsSetupHeaders.set("DPoP", firstSmsSetupProof);
            firstSmsSetupHeaders.set("Content-Type", "application/json");
            ResponseEntity<Map> firstSmsSetupResponse = restTemplate.exchange(
                    firstSmsSetupUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("phoneNumber", "+49 170 1234567"), firstSmsSetupHeaders),
                    Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> firstSmsNext = (Map<String, Object>) firstSmsSetupResponse.getBody().get("next");
            Long firstSmsSetupId = ((Number) firstSmsNext.get("smsSetupId")).longValue();
            AuthSmsSetup firstSmsSetup = authSmsSetupRepository.findById(firstSmsSetupId).orElseThrow();

            String firstSmsVerifyUrl = setupUrl + "/" + firstRegistrationSessionId + "/authentication-methods/sms/verify";
            String firstSmsVerifyProof = createDpopProof(firstKey, "POST", firstSmsVerifyUrl);
            HttpHeaders firstSmsVerifyHeaders = new HttpHeaders();
            firstSmsVerifyHeaders.set("DPoP", firstSmsVerifyProof);
            firstSmsVerifyHeaders.set("Content-Type", "application/json");
            restTemplate.exchange(
                    firstSmsVerifyUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("smsSetupId", firstSmsSetupId, "tan", firstSmsSetup.getTan()), firstSmsVerifyHeaders),
                    Map.class);
        }

        Account firstAccount = accountRepository.findByPersonId(person.getId()).orElseThrow();
        assertThat(firstAccount.getIdentifications()).hasSize(1);
        assertThat(firstAccount.getAuthenticationMethods()).hasSize(1);

        ECKey secondKey = new ECKeyGenerator(Curve.P_256).generate();

        String secondSessionsProof = createDpopProof(secondKey, "GET", sessionsUrl);
        HttpHeaders secondSessionsHeaders = new HttpHeaders();
        secondSessionsHeaders.set("DPoP", secondSessionsProof);
        ResponseEntity<SessionStatusResponse> secondSessionsResponse = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(secondSessionsHeaders),
                SessionStatusResponse.class);
        assertThat(secondSessionsResponse.getBody()).isNotNull();
        assertThat(secondSessionsResponse.getBody().next()).isNotNull();
        assertThat(secondSessionsResponse.getBody().next().step()).isEqualTo("registration");

        String secondSetupProof = createDpopProof(secondKey, "POST", setupUrl);
        HttpHeaders secondSetupHeaders = new HttpHeaders();
        secondSetupHeaders.set("DPoP", secondSetupProof);
        ResponseEntity<Map> secondSetupResponse = restTemplate.exchange(
                setupUrl,
                HttpMethod.POST,
                new HttpEntity<>(secondSetupHeaders),
                Map.class);
        String secondRegistrationSessionId = (String) secondSetupResponse.getBody().get("sessionId");
        assertThat(secondRegistrationSessionId).isNotBlank();

        String secondIdentificationUrl = setupUrl + "/" + secondRegistrationSessionId + "/identification-methods/fsc";
        String secondIdentificationProof = createDpopProof(secondKey, "POST", secondIdentificationUrl);
        HttpHeaders secondIdentificationHeaders = new HttpHeaders();
        secondIdentificationHeaders.set("DPoP", secondIdentificationProof);
        secondIdentificationHeaders.set("Content-Type", "application/json");
        restTemplate.exchange(
                secondIdentificationUrl,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("kvnr", "C111111111", "name", "Doe", "vorname", "Jane"), secondIdentificationHeaders),
                Map.class);

        String secondFscProof = createDpopProof(secondKey, "PATCH", secondIdentificationUrl);
        HttpHeaders secondFscHeaders = new HttpHeaders();
        secondFscHeaders.set("DPoP", secondFscProof);
        secondFscHeaders.set("Content-Type", "application/json");
        ResponseEntity<Map> secondFscResponse = restTemplate.exchange(
                secondIdentificationUrl,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("fsc", "REUSE123"), secondFscHeaders),
                Map.class);

        assertThat(secondFscResponse.getStatusCode().value()).isEqualTo(200);
        String authorisationSessionId = (String) secondFscResponse.getBody().get("sessionId");
        assertThat(authorisationSessionId).isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> secondFscNext = (Map<String, Object>) secondFscResponse.getBody().get("next");
        assertThat(secondFscNext.get("context")).isEqualTo("authentication");
        assertThat(secondFscNext.get("step")).isEqualTo("selectMethod");
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) secondFscNext.get("authenticationMethods");
        assertThat(methods).containsExactly("sms");

        String authSessionsProof = createDpopProof(secondKey, "GET", sessionsUrl);
        HttpHeaders authSessionsHeaders = new HttpHeaders();
        authSessionsHeaders.set("DPoP", authSessionsProof);
        ResponseEntity<SessionStatusResponse> authSessionsResponse = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(authSessionsHeaders),
                SessionStatusResponse.class);
        assertThat(authSessionsResponse.getBody().sessionId()).isNotNull();
        assertThat(authSessionsResponse.getBody().sessionId()).isNotEqualTo(UUID.fromString(authorisationSessionId));
        assertThat(authSessionsResponse.getBody().next()).isNotNull();
        assertThat(authSessionsResponse.getBody().next().step()).isEqualTo("selectMethod");

        UUID refreshedAuthorisationSessionId = authSessionsResponse.getBody().sessionId();
        String challengeUrl = "http://localhost:" + port + "/orchestrator/sessions/" + refreshedAuthorisationSessionId + "/authentication-methods/sms";
        String challengeProof = createDpopProof(secondKey, "POST", challengeUrl);
        HttpHeaders challengeHeaders = new HttpHeaders();
        challengeHeaders.set("DPoP", challengeProof);
        challengeHeaders.set("Content-Type", "application/json");
        ResponseEntity<Map> challengeResponse = restTemplate.exchange(
                challengeUrl,
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), challengeHeaders),
                Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> challengeNext = (Map<String, Object>) challengeResponse.getBody().get("next");
        assertThat(challengeNext.get("step")).isEqualTo("smsTanInput");
        Long secondSmsSetupId = ((Number) challengeNext.get("smsSetupId")).longValue();
        AuthSmsSetup secondSmsSetup = authSmsSetupRepository.findById(secondSmsSetupId).orElseThrow();
        assertThat(secondSmsSetup.getPhoneNumber()).isEqualTo("+491701234567");

        String verifyUrl = "http://localhost:" + port + "/orchestrator/sessions/" + refreshedAuthorisationSessionId + "/authentication-methods/sms/verify";
        String verifyProof = createDpopProof(secondKey, "POST", verifyUrl);
        HttpHeaders verifyHeaders = new HttpHeaders();
        verifyHeaders.set("DPoP", verifyProof);
        verifyHeaders.set("Content-Type", "application/json");
        ResponseEntity<Map> verifyResponse = restTemplate.exchange(
                verifyUrl,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("smsSetupId", secondSmsSetupId, "tan", secondSmsSetup.getTan()), verifyHeaders),
                Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> verifyNext = (Map<String, Object>) verifyResponse.getBody().get("next");
        assertThat(verifyNext.get("context")).isEqualTo("authentication");
        assertThat(verifyNext.get("step")).isEqualTo("authenticated");

        Account reusedAccount = accountRepository.findByPersonId(person.getId()).orElseThrow();
        assertThat(reusedAccount.getId()).isEqualTo(firstAccount.getId());
        assertThat(reusedAccount.getIdentifications()).hasSize(2);
        assertThat(reusedAccount.getAuthenticationMethods()).hasSize(1);
    }

    @Test
    void rejectsReplayOfSameDpopProof() throws Exception {
        ECKey ecKey = new ECKeyGenerator(Curve.P_256).generate();
        String sessionsUrl = "http://localhost:" + port + "/orchestrator/sessions";
        String sessionsProof = createDpopProof(ecKey, "GET", sessionsUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.set("DPoP", sessionsProof);

        ResponseEntity<SessionStatusResponse> firstResponse = restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                SessionStatusResponse.class);

        assertThat(firstResponse.getStatusCode().value()).isEqualTo(200);

        assertThatThrownBy(() -> restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class)
                .hasMessageContaining("replay");
    }

    @Test
    void rejectsTooOldDpopProof() throws Exception {
        ECKey ecKey = new ECKeyGenerator(Curve.P_256).generate();
        String sessionsUrl = "http://localhost:" + port + "/orchestrator/sessions";
        Instant oldIat = Instant.now().minus(10, ChronoUnit.MINUTES);
        String sessionsProof = createDpopProof(ecKey, "GET", sessionsUrl, oldIat);

        HttpHeaders headers = new HttpHeaders();
        headers.set("DPoP", sessionsProof);

        assertThatThrownBy(() -> restTemplate.exchange(
                sessionsUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class)
                .hasMessageContaining("too old");
    }

    private String createDpopProof(ECKey ecKey, String method, String url) throws Exception {
        return createDpopProof(ecKey, method, url, Instant.now());
    }

    private String createDpopProof(ECKey ecKey, String method, String url, Instant issuedAt) throws Exception {
        JWK publicJwk = ecKey.toPublicJWK();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new com.nimbusds.jose.JOSEObjectType("dpop+jwt"))
                .jwk(publicJwk)
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt.truncatedTo(ChronoUnit.SECONDS)))
                .claim("htm", method)
                .claim("htu", url)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new ECDSASigner(ecKey));
        return signedJWT.serialize();
    }
}

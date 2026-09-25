package com.example.dpop.kcext;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review 2026-09, M-9: an answer is believed only when the orchestrator signed exactly it, for
 * exactly this request. The signature is built here the way {@code KeycloakResponseSigner} builds it.
 */
class OrchestratorResponseVerifierTest {

    private static final String ORCHESTRATOR = "dpop-demo-orchestrator";
    private static final String KEYCLOAK = "dpop-demo-keycloak";
    private static final byte[] BODY = "{\"authData\":{\"accountId\":7}}".getBytes(StandardCharsets.UTF_8);

    private final ECKey orchestratorKey = key("orchestrator-response-1");
    private final OrchestratorResponseVerifier verifier = new OrchestratorResponseVerifier(
            new ImmutableJWKSet<SecurityContext>(new JWKSet(orchestratorKey.toPublicJWK())), ORCHESTRATOR, KEYCLOAK);

    @Test
    void theOrchestratorsAnswerToThisRequestIsBelieved() {
        assertDoesNotThrow(() -> verifier.verify(sign(orchestratorKey, "jti-1", 200, BODY), "jti-1", 200, BODY));
    }

    @Test
    void anAnswerRecordedForAnotherRequestIsRefused() throws Exception {
        String signature = sign(orchestratorKey, "jti-other", 200, BODY);
        assertThrows(OrchestratorResponseVerifier.OrchestratorResponseRejectedException.class,
                () -> verifier.verify(signature, "jti-1", 200, BODY));
    }

    @Test
    void aChangedBodyIsRefused() throws Exception {
        String signature = sign(orchestratorKey, "jti-1", 200, BODY);
        byte[] forged = "{\"authData\":{\"accountId\":8}}".getBytes(StandardCharsets.UTF_8);
        assertThrows(OrchestratorResponseVerifier.OrchestratorResponseRejectedException.class,
                () -> verifier.verify(signature, "jti-1", 200, forged));
    }

    @Test
    void aChangedStatusIsRefused() throws Exception {
        String signature = sign(orchestratorKey, "jti-1", 409, BODY);
        assertThrows(OrchestratorResponseVerifier.OrchestratorResponseRejectedException.class,
                () -> verifier.verify(signature, "jti-1", 200, BODY));
    }

    @Test
    void anUnsignedAnswerIsRefused() {
        assertThrows(OrchestratorResponseVerifier.OrchestratorResponseRejectedException.class,
                () -> verifier.verify(null, "jti-1", 200, BODY));
    }

    @Test
    void anAnswerSignedWithAnotherKeyIsRefused() throws Exception {
        String signature = sign(key("orchestrator-response-1"), "jti-1", 200, BODY);
        assertThrows(OrchestratorResponseVerifier.OrchestratorResponseRejectedException.class,
                () -> verifier.verify(signature, "jti-1", 200, BODY));
    }

    private static ECKey key(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(ECKey key, String jti, int status, byte[] body) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ORCHESTRATOR)
                .audience(KEYCLOAK)
                .issueTime(new Date(now))
                .expirationTime(new Date(now + 60_000))
                .claim("req", jti)
                .claim("status", status)
                .claim("body_sha256", Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body)).toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID())
                .type(new JOSEObjectType("orchestrator-response+jwt")).build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}

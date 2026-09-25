package com.example.dpop.kcext;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks that an answer really comes from the orchestrator before this extension believes it
 * (review 2026-09, M-9): the peer-auth assertion only authenticates the REQUEST, but the answer
 * decides who gets logged in. The orchestrator signs every answer to a peer-auth request
 * ({@code KeycloakResponseSigner}); this verifies that signature, that it answers THIS request
 * ({@code req} = the assertion's jti), and that status and body are exactly the ones received.
 * Anything else is refused as if the orchestrator had not answered at all.
 */
final class OrchestratorResponseVerifier {

    static final String HEADER = "Orchestrator-Response-Signature";
    private static final JOSEObjectType TYPE = new JOSEObjectType("orchestrator-response+jwt");
    private static final String JWKS_PATH = "/orchestrator/api/v1/kc/response-jwks/.well-known/jwks.json";
    private static final long MAX_CLOCK_SKEW_MILLIS = 300_000;

    /** One cached key source per orchestrator - clients are created per call, the keys must not be fetched per call. */
    private static final Map<String, JWKSource<SecurityContext>> SOURCES = new ConcurrentHashMap<>();

    private final JWKSource<SecurityContext> keys;
    private final String orchestratorId;
    private final String keycloakId;

    /**
     * @param orchestratorId what the orchestrator calls itself - the audience of our peer-auth assertions
     * @param keycloakId what we call ourselves - the issuer of our peer-auth assertions
     */
    static OrchestratorResponseVerifier forOrchestrator(String baseUrl, String orchestratorId, String keycloakId) {
        JWKSource<SecurityContext> source = SOURCES.computeIfAbsent(baseUrl, url -> {
            try {
                return JWKSourceBuilder.create(URI.create(url + JWKS_PATH).toURL()).retrying(true).build();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid orchestrator base URL: " + url, e);
            }
        });
        return new OrchestratorResponseVerifier(source, orchestratorId, keycloakId);
    }

    OrchestratorResponseVerifier(JWKSource<SecurityContext> keys, String orchestratorId, String keycloakId) {
        this.keys = keys;
        this.orchestratorId = orchestratorId;
        this.keycloakId = keycloakId;
    }

    void verify(String signatureHeader, String requestJti, int status, byte[] body) throws OrchestratorResponseRejectedException {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new OrchestratorResponseRejectedException("answer carries no signature");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(signatureHeader);
            if (!TYPE.equals(jwt.getHeader().getType())) {
                throw new OrchestratorResponseRejectedException("unexpected signature type " + jwt.getHeader().getType());
            }
            List<JWK> candidates = keys.get(new JWKSelector(new JWKMatcher.Builder().keyID(jwt.getHeader().getKeyID()).build()), null);
            if (candidates.isEmpty() || !(candidates.get(0) instanceof ECKey key)) {
                throw new OrchestratorResponseRejectedException("unknown signing key " + jwt.getHeader().getKeyID());
            }
            if (!jwt.verify(new ECDSAVerifier(key))) {
                throw new OrchestratorResponseRejectedException("signature does not verify");
            }
            checkClaims(jwt.getJWTClaimsSet(), requestJti, status, body);
        } catch (com.nimbusds.jose.KeySourceException e) {
            throw new OrchestratorResponseRejectedException("orchestrator keys unavailable: " + e.getMessage());
        } catch (ParseException | JOSEException e) {
            throw new OrchestratorResponseRejectedException("signature unreadable: " + e.getMessage());
        }
    }

    private void checkClaims(JWTClaimsSet claims, String requestJti, int status, byte[] body) throws OrchestratorResponseRejectedException, ParseException {
        if (!orchestratorId.equals(claims.getIssuer())) {
            throw new OrchestratorResponseRejectedException("unexpected issuer " + claims.getIssuer());
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(keycloakId)) {
            throw new OrchestratorResponseRejectedException("not addressed to this Keycloak");
        }
        if (!Objects.equals(requestJti, claims.getStringClaim("req"))) {
            throw new OrchestratorResponseRejectedException("answers a different request");
        }
        Object signedStatus = claims.getClaim("status");
        if (!(signedStatus instanceof Number number) || number.intValue() != status) {
            throw new OrchestratorResponseRejectedException("status does not match");
        }
        if (!Objects.equals(sha256(body), claims.getStringClaim("body_sha256"))) {
            throw new OrchestratorResponseRejectedException("body does not match");
        }
        long now = System.currentTimeMillis();
        Date exp = claims.getExpirationTime();
        Date iat = claims.getIssueTime();
        if (exp == null || exp.getTime() + MAX_CLOCK_SKEW_MILLIS < now) {
            throw new OrchestratorResponseRejectedException("signature expired");
        }
        if (iat == null || iat.getTime() - MAX_CLOCK_SKEW_MILLIS > now) {
            throw new OrchestratorResponseRejectedException("signature issued in the future");
        }
    }

    private static String sha256(byte[] body) {
        try {
            return Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body)).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An answer that cannot be shown to be the orchestrator's - handled like no answer at all. */
    static final class OrchestratorResponseRejectedException extends IOException {
        OrchestratorResponseRejectedException(String reason) {
            super("Orchestrator answer rejected: " + reason);
        }
    }
}

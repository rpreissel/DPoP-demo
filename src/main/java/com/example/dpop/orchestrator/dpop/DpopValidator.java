package com.example.dpop.orchestrator.dpop;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

@Component
public class DpopValidator {

    private static final String DPOP_JWT_TYPE = "dpop+jwt";
    private static final Set<JWSAlgorithm> SUPPORTED_ALGORITHMS = Set.of(
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512,
            JWSAlgorithm.RS256,
            JWSAlgorithm.RS384,
            JWSAlgorithm.RS512
    );
    private static final long MAX_CLOCK_SKEW_SECONDS = 30;

    public DpopProof validate(String dpopProof, String httpMethod, String httpUrl) {
        if (dpopProof == null || dpopProof.isBlank()) {
            throw new DpopValidationException("Missing DPoP proof");
        }

        SignedJWT signedJWT;
        try {
            signedJWT = SignedJWT.parse(dpopProof);
        } catch (ParseException e) {
            throw new DpopValidationException("Invalid DPoP proof format", e);
        }

        JWSHeader header = signedJWT.getHeader();
        validateHeader(header);

        JWK jwk = header.getJWK();
        if (jwk == null || jwk.isPrivate()) {
            throw new DpopValidationException("DPoP proof must contain a public JWK");
        }

        JWTClaimsSet claims;
        try {
            claims = signedJWT.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new DpopValidationException("Invalid DPoP claims", e);
        }

        validateSignature(signedJWT, jwk, header.getAlgorithm());
        validateClaims(claims, httpMethod, httpUrl);

        try {
            return new DpopProof(
                    dpopProof,
                    jwk,
                    claims.getJWTID(),
                    claims.getStringClaim("htm"),
                    claims.getStringClaim("htu"),
                    claims.getIssueTime().toInstant(),
                    claims.getStringClaim("nonce")
            );
        } catch (ParseException e) {
            throw new DpopValidationException("Invalid DPoP claims", e);
        }
    }

    private void validateHeader(JWSHeader header) {
        if (!DPOP_JWT_TYPE.equalsIgnoreCase(header.getType().getType())) {
            throw new DpopValidationException("DPoP proof must have type 'dpop+jwt'");
        }
        if (!SUPPORTED_ALGORITHMS.contains(header.getAlgorithm())) {
            throw new DpopValidationException("Unsupported DPoP algorithm: " + header.getAlgorithm());
        }
    }

    private void validateSignature(SignedJWT signedJWT, JWK jwk, JWSAlgorithm algorithm) {
        try {
            boolean valid;
            if (jwk instanceof ECKey ecKey) {
                valid = signedJWT.verify(new ECDSAVerifier(ecKey.toECPublicKey()));
            } else {
                throw new DpopValidationException("Unsupported key type: " + jwk.getKeyType());
            }
            if (!valid) {
                throw new DpopValidationException("Invalid DPoP proof signature");
            }
        } catch (JOSEException e) {
            throw new DpopValidationException("Failed to verify DPoP proof signature", e);
        }
    }

    private void validateClaims(JWTClaimsSet claims, String httpMethod, String httpUrl) {
        try {
            String htm = claims.getStringClaim("htm");
            if (htm == null || !htm.equalsIgnoreCase(httpMethod)) {
                throw new DpopValidationException("DPoP htm claim does not match request method");
            }

            String htu = claims.getStringClaim("htu");
            if (htu == null) {
                throw new DpopValidationException("DPoP htu claim is missing");
            }
            if (!normalizeUrl(htu).equalsIgnoreCase(normalizeUrl(httpUrl))) {
                throw new DpopValidationException("DPoP htu claim does not match request URL");
            }

            Date issueTime = claims.getIssueTime();
            if (issueTime == null) {
                throw new DpopValidationException("DPoP iat claim is missing");
            }
            Instant issuedAt = issueTime.toInstant();
            Instant now = Instant.now();
            if (issuedAt.isAfter(now.plus(MAX_CLOCK_SKEW_SECONDS, ChronoUnit.SECONDS))) {
                throw new DpopValidationException("DPoP iat claim is in the future");
            }

            String jti = claims.getJWTID();
            if (jti == null || jti.isBlank()) {
                throw new DpopValidationException("DPoP jti claim is missing");
            }
        } catch (ParseException e) {
            throw new DpopValidationException("Invalid DPoP claims", e);
        }
    }

    private String normalizeUrl(String url) {
        int queryIndex = url.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        int fragmentIndex = withoutQuery.indexOf('#');
        return fragmentIndex >= 0 ? withoutQuery.substring(0, fragmentIndex) : withoutQuery;
    }
}

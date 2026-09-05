package com.example.dpop.kcext;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.UUID;

/**
 * Builds and signs the one JWT per kc-facade request (docs/ideen/web-keycloak-kanal.md #3) - the
 * server-side counterpart of the frontend's kcSigning.ts, now actually running where the real
 * Authenticator SPI plugin belongs. The {@code kc_session_id} claim is always the (eventual)
 * UserSessionModel id, present even before Keycloak has a `sub` - see
 * {@code ChannelSession.kcSessionId}'s own doc on the orchestrator side for why the initial-login
 * and step-up cases don't need separate claims here.
 */
final class PeerAuthAssertionSigner {

    private final String issuer;
    private final String audience;

    PeerAuthAssertionSigner(String issuer, String audience) {
        this.issuer = issuer;
        this.audience = audience;
    }

    String sign(String httpMethod, String httpUrl, String kcSessionId) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date(nowSeconds * 1000))
                .claim("htm", httpMethod)
                .claim("htu", httpUrl)
                .claim("kc_session_id", kcSessionId);

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(PeerAuthSigningKey.KEY.getKeyID()).build(),
                claims.build()
        );
        try {
            jwt.sign(new ECDSASigner(PeerAuthSigningKey.KEY));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to sign peer-auth assertion", e);
        }
        return jwt.serialize();
    }
}

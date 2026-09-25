package com.example.dpop.kcext;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.UUID;

/**
 * Builds and signs the one JWT per kc-facade request (docs/12-entscheidungen.md ADR-7,
 * docs/02-domaenenmodell.md Abschnitt 1) -
 * the server-side counterpart of the frontend's kcSigning.ts, now actually running where the real
 * Authenticator SPI plugin belongs. The {@code channel_anchor} claim is always THIS flow run's own
 * {@code channelSessionId} - unique per flow run, so two concurrent flows sharing the same
 * underlying SSO session (e.g. two tabs both stepping up at once) never share an anchor value.
 * {@code htu} already binds the assertion to the exact URL (channelSessionId included, where the
 * endpoint has one in its path) - the anchor claim is what additionally covers the endpoints that
 * don't (the facade-neutral {@code /tools/{toolSessionId}/...} ones), and is checked explicitly by
 * {@code KcChannelAccessGuard} either way. Keycloak's actual, durable UserSessionModel id is a
 * completely separate value, carried only where RestoreData needs it (see OrchestratorClient's own
 * doc) - never this claim.
 */
final class PeerAuthAssertionSigner {

    private final String issuer;
    private final String audience;
    private final ECKey signingKey;

    PeerAuthAssertionSigner(String issuer, String audience, ECKey signingKey) {
        this.issuer = issuer;
        this.audience = audience;
        this.signingKey = signingKey;
    }

    String sign(String httpMethod, String httpUrl, String channelSessionId) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date(nowSeconds * 1000))
                .claim("htm", httpMethod)
                .claim("htu", httpUrl)
                .claim("channel_anchor", channelSessionId);

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.getKeyID()).build(),
                claims.build()
        );
        try {
            jwt.sign(new ECDSASigner(signingKey));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to sign peer-auth assertion", e);
        }
        return jwt.serialize();
    }

    /** The jti of an assertion this signer produced - what the orchestrator's answer must name ({@code req}). */
    static String jtiOf(String assertion) {
        try {
            return SignedJWT.parse(assertion).getJWTClaimsSet().getJWTID();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("Own peer-auth assertion unreadable", e);
        }
    }
}

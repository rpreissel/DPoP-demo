package com.example.dpop.kcext;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.UUID;

/**
 * Builds and signs the one JWT per kc-facade request (docs/ideen/web-keycloak-kanal.md #3/#4) -
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

    PeerAuthAssertionSigner(String issuer, String audience) {
        this.issuer = issuer;
        this.audience = audience;
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

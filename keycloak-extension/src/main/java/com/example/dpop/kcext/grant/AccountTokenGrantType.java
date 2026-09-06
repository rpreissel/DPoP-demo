package com.example.dpop.kcext.grant;

import com.example.dpop.kcext.OrchestratorNotes;
import com.example.dpop.kcext.credential.OrchestratorPublicKeyCredential;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.Constants;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.protocol.oidc.grants.OAuth2GrantTypeBase;
import org.keycloak.services.CorsErrorResponseException;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * Custom OAuth 2.0 grant (DPoP-demo-xso) that mints a real, Keycloak-signed access token for one
 * account, without a shared admin secret standing in for that account. The orchestrator already
 * authenticates as its own confidential client (Keycloak's normal client-auth step, run before
 * grant dispatch, same as every built-in grant) - this grant additionally requires a per-account
 * signed assertion, proving the caller also holds THAT account's own private key
 * (AccountKeypairService on the orchestrator side). Compromising the shared client secret alone is
 * therefore not enough to mint a token for an arbitrary account; compromising one account's key
 * only ever affects that one account.
 *
 * Modeled after {@code ClientCredentialsGrantType} (same Keycloak version): a machine-to-machine
 * grant that never goes through the interactive {@code AuthenticationProcessor} flow, just builds
 * the user session directly - the "user" here is the target account, not the client's own service
 * account.
 */
public class AccountTokenGrantType extends OAuth2GrantTypeBase {

    private static final Logger logger = Logger.getLogger(AccountTokenGrantType.class);

    public static final String GRANT_TYPE = "urn:dpop-demo:account-token";
    public static final String ACCOUNT_ID_PARAM = "account_id";
    public static final String ASSERTION_PARAM = "assertion";
    public static final String ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId";
    private static final String SESSION_MARKER_NOTE = "dpop-demo-account-token-session";

    // Generous like kc.peer-auth.max-clock-skew-seconds on the orchestrator side (application-
    // keycloak.yml) - same podman-machine clock-drift environment, opposite direction.
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    @Override
    public Response process(Context context) {
        setContext(context);

        String accountId = formParams.getFirst(ACCOUNT_ID_PARAM);
        String assertion = formParams.getFirst(ASSERTION_PARAM);
        if (accountId == null || assertion == null) {
            return reject("Missing " + ACCOUNT_ID_PARAM + " or " + ASSERTION_PARAM);
        }

        UserModel user = findUserByAccountId(accountId);
        if (user == null || !user.isEnabled()) {
            return reject("Unknown or disabled account: " + accountId);
        }

        String publicKeyJwk = user.credentialManager()
                .getStoredCredentialsByTypeStream(OrchestratorPublicKeyCredential.TYPE)
                .findFirst()
                .map(org.keycloak.credential.CredentialModel::getSecretData)
                .orElse(null);
        if (publicKeyJwk == null) {
            return reject("Account has no registered public key: " + accountId);
        }

        JWTClaimsSet assertionClaims = verifyAssertion(assertion, accountId, publicKeyJwk);
        if (assertionClaims == null) {
            return reject("Invalid assertion for account: " + accountId);
        }

        event.user(user);
        event.detail(Details.USERNAME, user.getUsername());

        String scope = getRequestedScopes();

        RootAuthenticationSessionModel rootAuthSession = new AuthenticationSessionManager(session).createAuthenticationSession(realm, false);
        AuthenticationSessionModel authSession = rootAuthSession.createAuthenticationSession(client);
        authSession.setAuthenticatedUser(user);
        authSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        authSession.setClientNote(OIDCLoginProtocol.ISSUER, Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName()));
        authSession.setClientNote(OIDCLoginProtocol.SCOPE_PARAM, scope);

        // Reuses this account's own existing session across repeated grant calls (e.g. a later
        // step-up minting a fresh token) instead of creating a new one every time - same
        // underlying Keycloak session throughout an App-channel login, only the token itself is
        // re-minted (or, for a plain time-based renewal with no ACR/AMR change, refreshed via
        // Keycloak's own refresh_token grant instead of coming back through this one at all).
        UserSessionModel userSession = findExistingSession(user);
        if (userSession == null) {
            userSession = new UserSessionManager(session).createUserSession(
                    authSession.getParentSession().getId(), realm, user, user.getUsername(),
                    clientConnection.getRemoteHost(), "dpop-demo-account-token", false, null, null,
                    UserSessionModel.SessionPersistenceState.PERSISTENT);
            userSession.setNote(SESSION_MARKER_NOTE, "true");
        }
        // Same note keys OrchestratorAuthenticator writes on the WEB-channel side, read by the
        // already-registered OrchestratorAcrAmrMapper (client scope "orchestrator-claims") to put
        // acr/amr into the minted token - the orchestrator is the sole ACR/AMR authority for an
        // APP-channel account exactly like it is for a WEB-channel one, so the same claim-injection
        // mechanism applies unchanged. Read from the verified assertion's own claims, not a
        // separate form param, so they carry the same signature guarantee as accountId/exp.
        String acr = assertionClaims.getClaim("acr") != null ? assertionClaims.getClaim("acr").toString() : null;
        if (acr != null) {
            userSession.setNote(OrchestratorNotes.USER_SESSION_NOTE_ACR, acr);
        }
        try {
            List<String> amr = assertionClaims.getStringListClaim("amr");
            if (amr != null) {
                userSession.setNote(OrchestratorNotes.USER_SESSION_NOTE_AMR, String.join(",", amr));
            }
        } catch (ParseException e) {
            logger.debugf("Account-token assertion has an unparseable amr claim: %s", e.getMessage());
        }
        event.session(userSession);

        AuthenticationManager.setClientScopesInSession(session, authSession);
        ClientSessionContext clientSessionCtx = TokenManager.attachAuthenticationSession(session, userSession, authSession);
        clientSessionCtx.setAttribute(Constants.GRANT_TYPE, context.getGrantType());
        updateUserSessionFromClientAuth(userSession);

        return createTokenResponse(user, userSession, clientSessionCtx, scope, true, null);
    }

    /**
     * This account's own still-alive session from a PREVIOUS grant call, if any -
     * {@link #SESSION_MARKER_NOTE} tags exactly the sessions this grant itself created, so an
     * unrelated session the same user might hold some other way (there is none in this demo, but
     * nothing here should assume that) is never picked up by accident.
     */
    private UserSessionModel findExistingSession(UserModel user) {
        try (Stream<UserSessionModel> sessions = session.sessions().getUserSessionsStream(realm, user)) {
            return sessions.filter(s -> "true".equals(s.getNote(SESSION_MARKER_NOTE))).findFirst().orElse(null);
        }
    }

    private UserModel findUserByAccountId(String accountId) {
        try (Stream<UserModel> matches = session.users().searchForUserByUserAttributeStream(realm, ACCOUNT_ID_ATTRIBUTE, accountId)) {
            return matches.findFirst().orElse(null);
        }
    }

    /** The verified assertion's own claims (including acr/amr, if present) - {@code null} if signature, subject, audience, or expiry don't check out. */
    private JWTClaimsSet verifyAssertion(String assertion, String expectedAccountId, String publicKeyJwk) {
        try {
            ECKey publicKey = ECKey.parse(publicKeyJwk);
            SignedJWT jwt = SignedJWT.parse(assertion);
            if (!jwt.verify(new ECDSAVerifier(publicKey))) {
                return null;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!expectedAccountId.equals(claims.getSubject())) {
                return null;
            }
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(GRANT_TYPE)) {
                return null;
            }
            Date now = new Date();
            Date exp = claims.getExpirationTime();
            if (exp == null || now.getTime() - MAX_CLOCK_SKEW_SECONDS * 1000 > exp.getTime()) {
                return null;
            }
            return claims;
        } catch (ParseException e) {
            logger.debugf("Account-token assertion rejected: %s", e.getMessage());
            return null;
        } catch (com.nimbusds.jose.JOSEException e) {
            logger.debugf("Account-token assertion signature check failed: %s", e.getMessage());
            return null;
        }
    }

    private Response reject(String reason) {
        event.detail(Details.REASON, reason);
        event.error(Errors.INVALID_REQUEST);
        throw new CorsErrorResponseException(cors, OAuthErrorException.INVALID_GRANT, reason, Response.Status.BAD_REQUEST);
    }

    @Override
    public EventType getEventType() {
        return EventType.LOGIN;
    }

    // Lets Keycloak's own refresh_token grant renew the token later without re-signing/re-
    // verifying an account assertion, as long as the ACR/AMR baked into THIS session haven't
    // changed (docs/12-entscheidungen.md ADR-9) - KcTokenProvider only ever comes back through
    // THIS grant again when they have. Standard refresh also bumps lastSessionRefresh itself
    // (TokenManager.generateRefreshToken), which is what keeps this reused session's SSO Session
    // Idle timeout alive without any extra bookkeeping here.
    @Override
    protected boolean useRefreshToken() {
        return true;
    }
}

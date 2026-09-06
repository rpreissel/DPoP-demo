package com.example.dpop.kcext.credential;

import com.example.dpop.kcext.grant.AccountTokenGrantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * `POST /admin/realms/{realm}/orchestrator-keys/{accountId}` (DPoP-demo-xso): the write side of
 * {@link OrchestratorPublicKeyCredential} - mounted via the {@code AdminRealmResourceProvider} SPI
 * ({@link AccountPublicKeyResourceProviderFactory}), so Keycloak has already authenticated the
 * caller's Bearer token and resolved [auth] BEFORE this class ever runs, exactly like every other
 * `/admin/realms/{realm}/...` endpoint - no separate auth check to get wrong here, only the
 * per-user authorization ([auth.users().requireManage]) a real admin endpoint needs.
 */
public class AccountPublicKeyResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminPermissionEvaluator auth;

    public AccountPublicKeyResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth) {
        this.session = session;
        this.realm = realm;
        this.auth = auth;
    }

    /**
     * [body]'s `authMethods` (the account's current active authentication method names, e.g.
     * `["password","sms"]`) is purely informational - it is never read back by
     * {@link AccountTokenGrantType} or checked in any way, only stored alongside the key in
     * {@link CredentialModel#getCredentialData()} (the non-secret half of a credential, unlike
     * {@link CredentialModel#getSecretData()} which holds the key itself) so an admin or the
     * account owner, looking at this credential in Keycloak, can see what it theoretically
     * attests to without having to cross-reference the orchestrator's own account record.
     */
    @POST
    @Path("{accountId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setPublicKey(@PathParam("accountId") String accountId, String rawBody) {
        // Parsed manually via Jackson rather than a JAX-RS-bound Map<String, Object>/DTO
        // parameter: Keycloak's own REST provider stack (RESTEasy Reactive) fails with "Cannot
        // parse the JSON" on a body whose values aren't uniformly typed (observed live) - a raw
        // String body sidesteps its generic-type binding entirely.
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String publicKeyJwk = body.path("publicKeyJwk").asText(null);
        if (publicKeyJwk == null || publicKeyJwk.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        List<String> authMethods = new java.util.ArrayList<>();
        for (JsonNode methodNode : body.path("authMethods")) authMethods.add(methodNode.asText());

        UserModel user = findUserByAccountId(accountId);
        if (user == null) {
            throw new NotFoundException("No user for accountId=" + accountId);
        }
        auth.users().requireManage(user);

        // Idempotent overwrite: a re-sync must replace the previous key/method-list, never
        // accumulate one credential row per sync run.
        user.credentialManager().getStoredCredentialsByTypeStream(OrchestratorPublicKeyCredential.TYPE)
                .forEach(existing -> user.credentialManager().removeStoredCredentialById(existing.getId()));

        CredentialModel credential = new CredentialModel();
        credential.setType(OrchestratorPublicKeyCredential.TYPE);
        credential.setSecretData(publicKeyJwk);
        credential.setCredentialData(toCredentialData(authMethods));
        credential.setCreatedDate(System.currentTimeMillis());
        user.credentialManager().createStoredCredential(credential);

        return Response.noContent().build();
    }

    private String toCredentialData(List<String> authMethods) {
        try {
            return MAPPER.writeValueAsString(Map.of("authMethods", authMethods));
        } catch (Exception e) {
            return "{}";
        }
    }

    private UserModel findUserByAccountId(String accountId) {
        try (Stream<UserModel> matches = session.users()
                .searchForUserByUserAttributeStream(realm, AccountTokenGrantType.ACCOUNT_ID_ATTRIBUTE, accountId)) {
            return matches.findFirst().orElse(null);
        }
    }
}

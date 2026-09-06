package com.example.dpop.kcext.credential;

/**
 * Shared constant for the per-account public-key credential (DPoP-demo-xso): stored as a genuine
 * {@link org.keycloak.credential.CredentialModel} on the user (via
 * {@link org.keycloak.models.SubjectCredentialManager#createStoredCredential}, written through
 * {@link AccountPublicKeyResource}), not a plain user attribute - a leaked/misconfigured attribute
 * export is a much easier way to walk off with key material than the credential store, which is
 * where every other proof-of-possession secret in Keycloak already lives. Never used for actual
 * Keycloak login (no {@code CredentialProvider} registered for it) - only
 * {@link com.example.dpop.kcext.grant.AccountTokenGrantType} ever reads it back, to verify the
 * orchestrator's signed assertion for that account.
 */
public final class OrchestratorPublicKeyCredential {

    public static final String TYPE = "orchestrator-public-key";

    private OrchestratorPublicKeyCredential() {
    }
}

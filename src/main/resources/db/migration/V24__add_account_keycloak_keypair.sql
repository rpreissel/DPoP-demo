-- Per-account asymmetric keypair for the profile-dependent real-token-retrieval grant
-- (DPoP-demo-xso): the public half is mirrored onto the account's Keycloak user as an attribute,
-- the private half stays here and signs the assertion the orchestrator presents to Keycloak's
-- custom account-token grant. Demo-only: the private key is stored in plaintext (DPoP-demo-xso.1
-- follow-up: encrypt at rest) - never returned to any API caller.
CREATE TABLE account_keycloak_keypair (
    account_id BIGINT PRIMARY KEY,
    public_key_jwk  VARCHAR(2000) NOT NULL,
    private_key_jwk VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

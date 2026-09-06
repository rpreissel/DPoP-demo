-- refresh_token_handle held only the short opaque mock secret (V14, "mockrt_<uuid>") until now.
-- KcTokenProvider (DPoP-demo-xso) stores a real, full-size Keycloak refresh_token JWT here too -
-- same widening V14 already did for token_handle when it stopped being a short handle.
ALTER TABLE auth_context ALTER COLUMN refresh_token_handle SET DATA TYPE VARCHAR(4096);

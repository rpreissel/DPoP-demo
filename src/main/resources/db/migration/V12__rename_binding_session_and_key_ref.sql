ALTER TABLE IF EXISTS client_session RENAME TO binding_session;
ALTER TABLE IF EXISTS account_jwk_mapping RENAME TO account_binding_key_mapping;

ALTER TABLE IF EXISTS binding_session
    ALTER COLUMN IF EXISTS jwk_thumbprint RENAME TO binding_key_ref;

ALTER TABLE IF EXISTS account_binding_key_mapping
    ALTER COLUMN IF EXISTS jwk_thumbprint RENAME TO binding_key_ref;

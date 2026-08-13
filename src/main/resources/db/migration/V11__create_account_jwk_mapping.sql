CREATE TABLE account_jwk_mapping (
    jwk_thumbprint VARCHAR(64) PRIMARY KEY,
    account_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

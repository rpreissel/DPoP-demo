DROP TABLE IF EXISTS registration_session;
DROP TABLE IF EXISTS authorisation_session;

CREATE TABLE client_session (
    jwk_thumbprint VARCHAR(64) PRIMARY KEY,
    type VARCHAR(10) NOT NULL,
    expire_at TIMESTAMP NOT NULL,
    last_accessed TIMESTAMP NOT NULL,
    format VARCHAR(10) NOT NULL,
    data JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE registration_session (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    jwk_thumbprint VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP NOT NULL
);

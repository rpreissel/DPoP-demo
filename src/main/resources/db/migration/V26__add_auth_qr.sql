-- auth_qr module (docs/ideen/qr-login-ueber-app.md) -------------------------

-- The one long-lived credential row enroll-qr creates - a pure opt-in marker,
-- no secret, referenced only via EnrollmentRef (auth_qr_optin).
CREATE TABLE auth_qr_optin (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    created_at TIMESTAMP NOT NULL
);

-- The pairing request connecting a WEB auth-qr/auth-qr-lookup activation to
-- an APP confirm-qr-login decision. pairing_code IS the lookup capability
-- (8 chars, see docs section 6), not just a label.
CREATE TABLE qr_login_request (
    pairing_code VARCHAR(16) PRIMARY KEY,
    verification_code VARCHAR(8) NOT NULL,
    expected_account_id BIGINT,
    status VARCHAR(16) NOT NULL,
    resolving_account_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- Attempt-scoped module data, one table per tool (docs/06-ablaeufe.md #1 pattern).
CREATE TABLE enroll_qr_tool_data (
    tool_session_id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE auth_qr_tool_data (
    tool_session_id UUID PRIMARY KEY,
    pairing_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE auth_qr_lookup_tool_data (
    tool_session_id UUID PRIMARY KEY,
    pairing_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE confirm_qr_login_tool_data (
    tool_session_id UUID PRIMARY KEY,
    pairing_code VARCHAR(16),
    created_at TIMESTAMP NOT NULL
);

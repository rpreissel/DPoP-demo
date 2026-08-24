-- Lookup-based login: auth-sms-lookup / auth-password-lookup -------------
-- Attempt-scoped data only; the resolved account (if any) is only known
-- after the first PATCH, so account_id is nullable here unlike the
-- device-bound *_use_tool_data tables, which always know it up front.

CREATE TABLE auth_sms_lookup_tool_data (
    tool_session_id UUID PRIMARY KEY,
    account_id BIGINT,
    issued_tan_hash VARCHAR(255),
    tan_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE auth_password_lookup_tool_data (
    tool_session_id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL
);

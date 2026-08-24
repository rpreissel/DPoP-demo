-- Lookup-based login: auth-email-lookup -----------------------------------
-- Same shape as auth_sms_lookup_tool_data (V9): account_id is nullable
-- because it's only known after the first PATCH (email) resolved it.

CREATE TABLE auth_email_lookup_tool_data (
    tool_session_id UUID PRIMARY KEY,
    account_id BIGINT,
    issued_code_hash VARCHAR(255),
    code_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

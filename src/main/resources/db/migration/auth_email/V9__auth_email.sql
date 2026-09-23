-- Schema des Moduls `auth_email`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS auth_email;

-- =============================================================================================
-- auth_email (no enrollment table: the credential is the account's EMAIL anchor)
-- =============================================================================================

CREATE TABLE auth_email.confirm_tool_session (
    tool_session_id  UUID PRIMARY KEY,
    email            VARCHAR(255),
    issued_code_hash VARCHAR(64),
    code_expires_at  TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_confirm_tool_session_created_at ON auth_email.confirm_tool_session (created_at);

-- One row per enroll-email run. No columns of its own: activating the method proves nothing, the
-- address was already established by confirm-email.
CREATE TABLE auth_email.enroll_tool_session (
    tool_session_id UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_enroll_tool_session_created_at ON auth_email.enroll_tool_session (created_at);

CREATE TABLE auth_email.auth_tool_session (
    tool_session_id  UUID PRIMARY KEY,
    issued_code_hash VARCHAR(64),
    code_expires_at  TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_auth_tool_session_created_at ON auth_email.auth_tool_session (created_at);

CREATE TABLE auth_email.lookup_tool_session (
    tool_session_id  UUID PRIMARY KEY,
    account_id       BIGINT,
    issued_code_hash VARCHAR(64),
    code_expires_at  TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_lookup_tool_session_created_at ON auth_email.lookup_tool_session (created_at);

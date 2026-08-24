-- account module: email as a direct, canonical account attribute -----------
-- (deliberate exception to the module-owned-enrollment pattern - same treatment
-- as person_id: one canonical value per account, not a swappable credential)

ALTER TABLE account ADD COLUMN email VARCHAR(255);
ALTER TABLE account ADD COLUMN email_confirmed_at TIMESTAMP;
-- Standard SQL: NULLs don't count as duplicates, so this only enforces
-- uniqueness among CONFIRMED emails, not "at most one unconfirmed attempt".
CREATE UNIQUE INDEX idx_account_email ON account(email);

-- auth_email module ---------------------------------------------------------

-- Attempt-scoped enrollment data (unconfirmed email + hashed code).
CREATE TABLE enroll_email_tool_data (
    tool_session_id UUID PRIMARY KEY,
    email VARCHAR(255),
    issued_code_hash VARCHAR(255),
    code_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

-- Attempt-scoped auth data (hashed code only - no enrollment reference,
-- since the confirmed email lives directly on account, not in this module).
CREATE TABLE auth_email_use_tool_data (
    tool_session_id UUID PRIMARY KEY,
    issued_code_hash VARCHAR(255),
    code_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

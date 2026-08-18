-- V14__add_channel_state_and_auth_context.sql
-- Add channel state management and AuthContext entity

-- Create auth_context table
CREATE TABLE auth_context (
    auth_context_id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL,
    keycloak_session_id VARCHAR(255),
    keycloak_subject VARCHAR(255),
    token_handle VARCHAR(255),
    current_acr VARCHAR(50),
    current_amr CLOB,
    auth_time TIMESTAMP NOT NULL,
    token_expires_at TIMESTAMP,
    refresh_expires_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_auth_context_account_id ON auth_context(account_id);
CREATE INDEX idx_auth_context_keycloak_session_id ON auth_context(keycloak_session_id);

-- Alter channel_session table to add state and auth_context_id
ALTER TABLE channel_session ADD COLUMN state VARCHAR(50) DEFAULT 'ANONYMOUS' NOT NULL;
ALTER TABLE channel_session ADD COLUMN auth_context_id UUID;
ALTER TABLE channel_session ADD CONSTRAINT fk_channel_session_auth_context_id FOREIGN KEY (auth_context_id) REFERENCES auth_context(auth_context_id);

CREATE INDEX idx_channel_session_state ON channel_session(state);
CREATE INDEX idx_channel_session_auth_context_id ON channel_session(auth_context_id);

-- Alter process_session table to add missing fields and pending_challenge
ALTER TABLE process_session ADD COLUMN pending_challenge CLOB;

-- Alter orchestrator_attempt table to add missing_fields and result columns
ALTER TABLE orchestrator_attempt ADD COLUMN missing_fields CLOB;
ALTER TABLE orchestrator_attempt ADD COLUMN result CLOB;

-- Update discriminator values in orchestrator_attempt to lowercase
UPDATE orchestrator_attempt SET attempt_type = 'identification' WHERE attempt_type = 'IDENTIFICATION';
UPDATE orchestrator_attempt SET attempt_type = 'authentication' WHERE attempt_type = 'AUTHENTICATION';

-- Update status values in orchestrator_attempt to new enum values
UPDATE orchestrator_attempt SET status = 'INPUT_REQUIRED' WHERE status = 'ACTIVE';
UPDATE orchestrator_attempt SET status = 'VERIFIED' WHERE status = 'PENDING_VERIFICATION';
UPDATE orchestrator_attempt SET status = 'FAILED' WHERE status = 'FAILED';
UPDATE orchestrator_attempt SET status = 'EXPIRED' WHERE status = 'EXPIRED';
UPDATE orchestrator_attempt SET status = 'CANCELLED' WHERE status = 'COMPLETED';

-- Update CHECK constraint for orchestrator_attempt status
-- Note: H2 doesn't support dropping individual constraints, so we work with what we have

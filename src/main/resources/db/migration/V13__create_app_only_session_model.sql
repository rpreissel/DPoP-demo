-- V13__create_app_only_session_model.sql
-- Creates new app-only orchestrator persistence model with ChannelSession, ProcessSession, and OrchestratorAttempt
-- Keeps old binding_session table for compatibility

-- Create ChannelSession table
CREATE TABLE channel_session (
    channel_session_id UUID PRIMARY KEY,
    channel VARCHAR(20) NOT NULL,
    binding_key_ref VARCHAR(64) NOT NULL UNIQUE,
    account_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (channel IN ('APP', 'WEB'))
);

CREATE INDEX idx_channel_session_binding_key_ref ON channel_session(binding_key_ref);
CREATE INDEX idx_channel_session_account_id ON channel_session(account_id);
CREATE INDEX idx_channel_session_expires_at ON channel_session(expires_at);

-- Create ProcessSession table (with SINGLE_TABLE inheritance)
CREATE TABLE process_session (
    process_session_id UUID PRIMARY KEY,
    channel_session_id UUID NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    account_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    -- RegistrationProcessSession columns
    person_id BIGINT,
    -- StepUpProcessSession columns
    required_acr VARCHAR(100),
    starting_acr VARCHAR(100),
    achieved_acr VARCHAR(100),
    -- Shared auth columns (Login, Registration Step2, StepUp)
    selected_identification_method VARCHAR(100),
    selected_authentication_method VARCHAR(100),
    FOREIGN KEY (channel_session_id) REFERENCES channel_session(channel_session_id),
    CHECK (purpose IN ('REGISTRATION', 'LOGIN', 'STEP_UP')),
    CHECK (status IN ('ACTIVE', 'PENDING_VERIFICATION', 'COMPLETED', 'EXPIRED', 'FAILED'))
);

CREATE INDEX idx_process_session_channel_session_id ON process_session(channel_session_id);
CREATE INDEX idx_process_session_purpose ON process_session(purpose);
CREATE INDEX idx_process_session_status ON process_session(status);
CREATE INDEX idx_process_session_expires_at ON process_session(expires_at);

-- Create OrchestratorAttempt table (with SINGLE_TABLE inheritance)
CREATE TABLE orchestrator_attempt (
    attempt_id UUID PRIMARY KEY,
    process_session_id UUID NOT NULL,
    attempt_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    next_context VARCHAR(50),
    next_step VARCHAR(50),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (process_session_id) REFERENCES process_session(process_session_id),
    CHECK (status IN ('ACTIVE', 'PENDING_VERIFICATION', 'COMPLETED', 'FAILED', 'EXPIRED')),
    CHECK (attempt_type IN ('IDENTIFICATION', 'AUTHENTICATION'))
);

CREATE INDEX idx_orchestrator_attempt_process_session_id ON orchestrator_attempt(process_session_id);
CREATE INDEX idx_orchestrator_attempt_status ON orchestrator_attempt(status);
CREATE INDEX idx_orchestrator_attempt_expires_at ON orchestrator_attempt(expires_at);


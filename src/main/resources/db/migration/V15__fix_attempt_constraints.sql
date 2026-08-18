-- V15__fix_attempt_constraints.sql
-- Fix CHECK constraints on orchestrator_attempt to match new AttemptStatus enum
-- and lowercase attempt_type discriminator values

-- Recreate orchestrator_attempt with updated constraints
-- (H2 does not support DROP CONSTRAINT on auto-named constraints, so we recreate)

CREATE TABLE orchestrator_attempt_new (
    attempt_id UUID PRIMARY KEY,
    process_session_id UUID NOT NULL,
    attempt_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    next_context VARCHAR(50),
    next_step VARCHAR(50),
    missing_fields CLOB,
    result CLOB,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (process_session_id) REFERENCES process_session(process_session_id),
    CHECK (status IN ('INPUT_REQUIRED', 'VERIFIED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    CHECK (attempt_type IN ('identification', 'authentication'))
);

INSERT INTO orchestrator_attempt_new
    (attempt_id, process_session_id, attempt_type, status, next_context, next_step,
     missing_fields, result, created_at, expires_at, retry_count, version)
SELECT attempt_id, process_session_id, attempt_type, status, next_context, next_step,
       missing_fields, result, created_at, expires_at, retry_count, version
FROM orchestrator_attempt;

DROP TABLE orchestrator_attempt;

ALTER TABLE orchestrator_attempt_new RENAME TO orchestrator_attempt;

CREATE INDEX idx_orchestrator_attempt_process_session_id ON orchestrator_attempt(process_session_id);
CREATE INDEX idx_orchestrator_attempt_status ON orchestrator_attempt(status);
CREATE INDEX idx_orchestrator_attempt_expires_at ON orchestrator_attempt(expires_at);

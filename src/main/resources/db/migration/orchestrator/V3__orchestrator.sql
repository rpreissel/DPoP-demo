-- Schema des Moduls `orchestrator`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS orchestrator;

-- =============================================================================================
-- orchestrator (core)
-- =============================================================================================

-- Evidence proven on one channel; cleared at logout. current ACR is never stored, always derived.
CREATE TABLE orchestrator.auth_evidence (
    id           UUID   PRIMARY KEY,
    account_id   BIGINT NOT NULL,
    amr_evidence JSON   NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_auth_evidence_account_id ON orchestrator.auth_evidence (account_id);

-- APP-channel token bookkeeping.
CREATE TABLE orchestrator.auth_context (
    id                   UUID   PRIMARY KEY,
    account_id           BIGINT NOT NULL,
    auth_evidence_id     UUID,
    keycloak_session_id  VARCHAR(64),
    -- Both hold the token itself (a full JWT under the keycloak profile), not an opaque handle -
    -- hence the length. Cache only: cleared whenever the evidence changes (AuthEvidenceService),
    -- so a step-up can never keep renewing a session on pre-step-up acr/amr.
    access_token         VARCHAR(4096),
    refresh_token        VARCHAR(4096),
    auth_time            TIMESTAMP WITH TIME ZONE NOT NULL,
    access_expires_at    TIMESTAMP WITH TIME ZONE,
    refresh_expires_at   TIMESTAMP WITH TIME ZONE,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_auth_context_auth_evidence FOREIGN KEY (auth_evidence_id) REFERENCES orchestrator.auth_evidence (id)
);
CREATE INDEX ix_auth_context_account_id ON orchestrator.auth_context (account_id);
CREATE INDEX ix_auth_context_auth_evidence_id ON orchestrator.auth_context (auth_evidence_id);

CREATE TABLE orchestrator.channel_session (
    id                    UUID        PRIMARY KEY,
    channel               VARCHAR(32) NOT NULL,
    -- APP channels anchor on the DPoP key, KEYCLOAK channels have none (docs/02-domaenenmodell.md #1).
    binding_key_ref       VARCHAR(64),
    channel_anchor        VARCHAR(64),
    kc_durable_session_id VARCHAR(64),
    account_id            BIGINT,
    auth_context_id       UUID,
    auth_evidence_id      UUID,
    state                 VARCHAR(32) NOT NULL,
    acr_floor             VARCHAR(16),
    entry_intent          VARCHAR(32) NOT NULL,
    available_tools       JSON        NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    last_accessed_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_channel_session_binding_key CHECK ((channel = 'APP') = (binding_key_ref IS NOT NULL)),
    CONSTRAINT fk_channel_session_auth_context FOREIGN KEY (auth_context_id) REFERENCES orchestrator.auth_context (id),
    CONSTRAINT fk_channel_session_auth_evidence FOREIGN KEY (auth_evidence_id) REFERENCES orchestrator.auth_evidence (id)
);
CREATE INDEX ix_channel_session_account_id ON orchestrator.channel_session (account_id);
CREATE INDEX ix_channel_session_expires_at ON orchestrator.channel_session (expires_at);
CREATE INDEX ix_channel_session_channel_expires_at ON orchestrator.channel_session (channel, expires_at);
CREATE INDEX ix_channel_session_auth_context_id ON orchestrator.channel_session (auth_context_id);
CREATE INDEX ix_channel_session_auth_evidence_id ON orchestrator.channel_session (auth_evidence_id);

-- One run of one AuthIntent. state_type keeps the JourneyState queryable; there is deliberately no
-- next_* column, next is derived from state. parent_journey_id has no FK: parent and child age out
-- independently in retention batches.
CREATE TABLE orchestrator.auth_journey (
    id                 UUID         PRIMARY KEY,
    channel_session_id UUID         NOT NULL,
    parent_journey_id  UUID,
    intent             VARCHAR(32)  NOT NULL,
    lifecycle          VARCHAR(32)  NOT NULL,
    account_id         BIGINT,
    state_type         VARCHAR(100) NOT NULL,
    state              JSON         NOT NULL,
    attempt_budget     INT          NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at        TIMESTAMP WITH TIME ZONE,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_auth_journey_channel_session FOREIGN KEY (channel_session_id) REFERENCES orchestrator.channel_session (id)
);
CREATE INDEX ix_auth_journey_channel_session ON orchestrator.auth_journey (channel_session_id, lifecycle, created_at);
CREATE INDEX ix_auth_journey_expires_at ON orchestrator.auth_journey (expires_at);
CREATE INDEX ix_auth_journey_consumed_at ON orchestrator.auth_journey (consumed_at);

-- Lifecycle metadata only; toolId comes from the route, tool data lives in the module.
CREATE TABLE orchestrator.tool_session (
    id         UUID PRIMARY KEY,
    journey_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_tool_session_auth_journey FOREIGN KEY (journey_id) REFERENCES orchestrator.auth_journey (id)
);
CREATE INDEX ix_tool_session_journey_id ON orchestrator.tool_session (journey_id);
CREATE INDEX ix_tool_session_expires_at ON orchestrator.tool_session (expires_at);

CREATE TABLE orchestrator.device_account_link (
    binding_key_ref VARCHAR(64) PRIMARY KEY,
    account_id      BIGINT      NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_device_account_link_account_id ON orchestrator.device_account_link (account_id);

-- Minimized audit trail. Session ids are historical values, not references: it outlives them.
CREATE TABLE orchestrator.session_event (
    id                 UUID         PRIMARY KEY,
    channel_session_id UUID,
    journey_id         UUID,
    event_type         VARCHAR(100) NOT NULL,
    source             VARCHAR(100) NOT NULL,
    payload_hash       VARCHAR(128),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_session_event_created_at ON orchestrator.session_event (created_at);

-- Rich debugging trace, deliberately not minimized (unlike orchestrator.session_event); short retention.
CREATE TABLE orchestrator.journey_log (
    id                 UUID         PRIMARY KEY,
    channel_session_id UUID         NOT NULL,
    journey_id         UUID,
    parent_journey_id  UUID,
    account_id         BIGINT,
    binding_key_ref    VARCHAR(64),
    channel_type       VARCHAR(32),
    intent             VARCHAR(32),
    event_type         VARCHAR(100) NOT NULL,
    journey_state      VARCHAR(100),
    detail             JSON,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_journey_log_channel_session_id ON orchestrator.journey_log (channel_session_id, created_at);
CREATE INDEX ix_journey_log_account_id ON orchestrator.journey_log (account_id, created_at);
CREATE INDEX ix_journey_log_binding_key_ref ON orchestrator.journey_log (binding_key_ref, created_at);
CREATE INDEX ix_journey_log_created_at ON orchestrator.journey_log (created_at);

-- One counter per (scope, subject); scope is part of the key so the subject spaces never collide.
CREATE TABLE orchestrator.attempt_throttle (
    scope        VARCHAR(32)  NOT NULL,
    subject      VARCHAR(128) NOT NULL,
    failed_count INT          NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_attempt_throttle PRIMARY KEY (scope, subject)
);
CREATE INDEX ix_attempt_throttle_updated_at ON orchestrator.attempt_throttle (updated_at);

-- The primary key insert IS the replay check. Keyed by SHA-256(thumbprint:jti): fixed width, and a
-- client-chosen jti can neither overflow the key nor bloat the hottest index in the system.
CREATE TABLE orchestrator.dpop_proof_replay (
    proof_hash VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_dpop_proof_replay_expires_at ON orchestrator.dpop_proof_replay (expires_at);

-- Operator settings per tool AND channel type (APP / KEYCLOAK): a runtime kill-switch and the
-- tool's rank in that channel's selection lists. No row = enabled, unranked.
CREATE TABLE orchestrator.tool_availability (
    tool_id    VARCHAR(50) NOT NULL,
    channel    VARCHAR(32) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    reason     VARCHAR(255),
    position   INTEGER,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_tool_availability PRIMARY KEY (tool_id, channel)
);

-- One row per runtime feature flag; no row = off, the documented default of that flag. flag_key
-- is a FeatureFlags constant (e.g. 'register-enroll-first'), never free-form: a strategy reads the
-- name from that object, so a typo here simply leaves the flag off rather than inventing one.
CREATE TABLE orchestrator.feature_flag (
    flag_key   VARCHAR(100) PRIMARY KEY,
    enabled    BOOLEAN      NOT NULL,
    reason     VARCHAR(255),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- keycloak profile: per-account keypair for the account-token grant. Demo-only: private key in
-- plaintext. Removed by KeycloakAccountSyncListener once the account deletion has committed.
CREATE TABLE orchestrator.keycloak_keypair (
    account_id      BIGINT        PRIMARY KEY,
    public_key_jwk  VARCHAR(2000) NOT NULL,
    private_key_jwk VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

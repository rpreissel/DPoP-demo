-- Reichhaltiges Journey-Log (im Gegensatz zu session_event bewusst NICHT minimiert -
-- Debugging/Demo-Zweck, kein Audit-Trail) - siehe JourneyLogEntry.

CREATE TABLE journey_log (
    log_id UUID PRIMARY KEY,
    binding_key_ref VARCHAR(64) NOT NULL,
    channel_session_id UUID NOT NULL,
    journey_id UUID NOT NULL,
    parent_journey_id UUID,
    intent VARCHAR(20) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    detail JSON,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_journey_log_binding_key_ref ON journey_log(binding_key_ref, created_at);

-- Persist the originating facade on each journey-log row so old entries still show APP vs KEYCLOAK
-- even after the underlying channel session is gone.
ALTER TABLE journey_log ADD COLUMN channel_type VARCHAR(20);

UPDATE journey_log
SET channel_type = (
    SELECT cs.channel
    FROM channel_session cs
    WHERE cs.channel_session_id = journey_log.channel_session_id
)
WHERE channel_type IS NULL;

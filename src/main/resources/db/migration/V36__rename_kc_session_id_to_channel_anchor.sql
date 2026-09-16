-- C3: the column held ChannelSession.channelAnchor (the peer-auth binding value, present on
-- EVERY channel, not just KEYCLOAK ones - docs/02-domaenenmodell.md Abschnitt 1), but was named
-- kc_session_id, which reads as Keycloak's own session id - exactly the field
-- durableKcSessionId/kc_durable_session_id actually is. Same confusion the 20-line doc comment on
-- ChannelSession.channelAnchor warns readers about, just invisible to anyone looking at SQL,
-- operations or forensics instead of the entity.

ALTER TABLE channel_session ALTER COLUMN kc_session_id RENAME TO channel_anchor;
ALTER INDEX idx_channel_session_kc_session_id RENAME TO idx_channel_session_channel_anchor;

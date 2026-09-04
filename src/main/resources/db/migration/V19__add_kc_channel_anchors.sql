-- WEB-Kanal-Anker (docs/ideen/web-keycloak-kanal.md #2): binding_key_ref bleibt APP-only und
-- wird nullable; WEB-Kanaele verankern stattdessen ueber kc_auth_session_id (initialer Login)
-- bzw. kc_session_id (Step-up).
ALTER TABLE channel_session ALTER COLUMN binding_key_ref DROP NOT NULL;
ALTER TABLE channel_session ADD COLUMN kc_auth_session_id VARCHAR(64);
ALTER TABLE channel_session ADD COLUMN kc_session_id VARCHAR(64);

CREATE INDEX idx_channel_session_kc_auth_session_id ON channel_session(kc_auth_session_id);
CREATE INDEX idx_channel_session_kc_session_id ON channel_session(kc_session_id);

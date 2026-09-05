-- kc_auth_session_id wird nicht mehr gebraucht: der kc-Anker ist jetzt immer kc_session_id, auch
-- beim initialen Login (die Extension verankert dort an authSession.getParentSession().getId(),
-- was Keycloak selbst als UserSessionModel-ID uebernimmt, sobald der Flow erfolgreich endet -
-- siehe ChannelSession.kcSessionId's Doc).
DROP INDEX idx_channel_session_kc_auth_session_id;
ALTER TABLE channel_session DROP COLUMN kc_auth_session_id;

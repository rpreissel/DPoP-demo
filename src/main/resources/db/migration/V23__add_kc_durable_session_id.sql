-- DPoP-demo-f9o.12: RetentionJob muss vor dem Loeschen einer abgelaufenen KEYCLOAK-ChannelSession
-- pruefen koennen, ob die zugehoerige Keycloak-Session noch lebt. channel_anchor (vormals
-- kc_session_id) ist dafuer NICHT geeignet - er ist immer nur dieser eine Flow-Durchlauf, nie
-- Keycloaks durable UserSessionModel-ID. Die durable ID wird erst am Ende eines erfolgreichen
-- Flow-Durchlaufs bekannt (OrchestratorResumeAuthenticator.onTopFlowSuccess -> restoreData-Aufruf,
-- docs/ideen/web-keycloak-kanal.md #6) - daher separat, nullable, und ohne eigenen Index (nur vom
-- Retention-Job gelesen, nie gesucht).
ALTER TABLE channel_session ADD COLUMN kc_durable_session_id VARCHAR(64);

-- D3: auth_context.keycloak_subject was never read or written anywhere in the codebase (unlike
-- its neighbor keycloak_session_id, which KcTokenProvider/JourneyService actively use under the
-- keycloak profile) - a dead field inviting future misinterpretation, not a placeholder worth
-- keeping for a shape nothing has ever needed.

ALTER TABLE auth_context DROP COLUMN keycloak_subject;

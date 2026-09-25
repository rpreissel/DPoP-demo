-- Invarianten aus docs/invarianten.md, die die Datenbank selbst durchsetzen kann (Review 2026-09,
-- Fahrplan Phase C, Schritt 15). Der Code haelt sie schon ein; die Constraints sind der Rauchmelder,
-- falls ein neuer Weg es einmal nicht tut. Vor jedem Constraint werden Altdaten bereinigt, die aus der
-- Zeit vor den Fixes stammen koennen - sonst scheiterte der Start mit einer bestehenden Datenbank.

-- I-3: hoechstens eine laufende (STARTED) Journey je Kanal. H2 kennt keine partiellen Indizes; eine
-- berechnete Spalte, die nur fuer laufende Journeys belegt ist, plus Unique-Index leistet dasselbe
-- (NULL zaehlt nicht als Wert).
UPDATE orchestrator.auth_journey j SET lifecycle = 'CANCELLED'
WHERE j.lifecycle = 'STARTED' AND EXISTS (
    SELECT 1 FROM orchestrator.auth_journey k
    WHERE k.channel_session_id = j.channel_session_id AND k.lifecycle = 'STARTED' AND k.created_at > j.created_at
);
ALTER TABLE orchestrator.auth_journey ADD COLUMN running_channel_session_id UUID
    GENERATED ALWAYS AS (CASE WHEN lifecycle = 'STARTED' THEN channel_session_id END);
CREATE UNIQUE INDEX ux_journey_running_per_channel ON orchestrator.auth_journey (running_channel_session_id);

-- I-1: ein beendeter Kanal (LOGGED_OUT, EXPIRED) haelt weder Tokens noch Evidenz.
UPDATE orchestrator.channel_session SET auth_context_id = NULL, auth_evidence_id = NULL
WHERE state IN ('LOGGED_OUT', 'EXPIRED');
ALTER TABLE orchestrator.channel_session ADD CONSTRAINT ck_channel_session_ended_without_login
    CHECK (state NOT IN ('LOGGED_OUT', 'EXPIRED') OR (auth_context_id IS NULL AND auth_evidence_id IS NULL));

-- I-4: ein angemeldeter Kanal hat Evidenz.
ALTER TABLE orchestrator.channel_session ADD CONSTRAINT ck_channel_session_authenticated_with_evidence
    CHECK (state <> 'AUTHENTICATED' OR auth_evidence_id IS NOT NULL);

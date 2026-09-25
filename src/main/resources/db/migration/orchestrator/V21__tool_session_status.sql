-- Wie eine ToolSession endet, als eigener Zustand statt als vorgezogene Ablaufzeit
-- (Review 2026-09, Fahrplan Phase C). Bisher hiess "fertig" oder "verlassen" nur: expires_at = jetzt.
-- RUNNING: der Schritt laeuft. DONE: mit Completed abgeschlossen - nie wieder abschliessbar (S-1).
-- ABANDONED: per Zurueck/Wechsel verlassen.
ALTER TABLE orchestrator.tool_session ADD COLUMN status VARCHAR(16) DEFAULT 'RUNNING' NOT NULL;
ALTER TABLE orchestrator.tool_session ADD CONSTRAINT ck_tool_session_status CHECK (status IN ('RUNNING', 'DONE', 'ABANDONED'));

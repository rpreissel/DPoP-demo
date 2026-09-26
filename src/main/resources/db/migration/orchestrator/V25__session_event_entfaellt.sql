-- session_event wurde nur geschrieben, nie gelesen (ausser vom Aufbewahrungsjob, der es loeschte).
-- Was darin Nachweis war, steht jetzt im Audit-Protokoll des Kontos (account.audit_event, ADR-39);
-- der Rest steht ausfuehrlicher im Journey-Log (Fehlersuche, 30 Tage).
DROP TABLE orchestrator.session_event;

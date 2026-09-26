-- account.identification geht im Audit-Protokoll auf (ADR-39, Review 2026-09): Methode, Niveau und
-- Zeitpunkt standen dort schon; neu sind die Rolle (source), die Nachweis-Referenz beim Anbieter
-- (reference) und der Hash des Gesehenen (evidence_hash). Eine Dokumentnummer wird nicht mehr
-- gespeichert (§ 20 PAuswG).
ALTER TABLE account.audit_event ADD COLUMN reference VARCHAR(255);
ALTER TABLE account.audit_event ADD COLUMN evidence_hash VARCHAR(128);

-- Identifizierungen von vor V24 haben noch kein Ereignis: sie werden mit Methode, Niveau und
-- Zeitpunkt uebernommen. Ihre Referenzen stehen im JSON-Feld details, das H2 hier nicht
-- zuverlaessig lesen kann - nur Demo-Bestand betroffen.
INSERT INTO account.audit_event (account_id, event_type, subject, acr, occurred_at)
SELECT i.account_id, 'IDENTIFIED', i.method, i.achieved_acr, i.identified_at
FROM account.identification i
WHERE NOT EXISTS (
    SELECT 1 FROM account.audit_event e
    WHERE e.account_id = i.account_id AND e.event_type = 'IDENTIFIED' AND e.subject = i.method
      AND e.occurred_at BETWEEN DATEADD('SECOND', -5, i.identified_at) AND DATEADD('SECOND', 5, i.identified_at)
);

DROP TABLE account.identification;

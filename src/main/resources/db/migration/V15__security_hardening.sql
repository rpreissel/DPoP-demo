-- Security-Audit-Nachzug (siehe Beads DPoP-demo-hpd/-lus/-5dy und Geschwister).

-- 1) Ein Throttle fuer mehrere Subjektarten -------------------------------
-- login_attempt_throttle konnte nur Konten zaehlen. Gebraucht werden drei
-- Achsen, die sich denselben Zaehl-/Sperrmechanismus teilen, aber NICHT
-- denselben Schluesselraum: ACCOUNT (AUTH-Versuche gegen ein Konto),
-- PERSON (IDENT-Versuche gegen eine Person - ident-fsc raet einen FSC und
-- uebernimmt bei Erfolg deren Konto) und BINDING_KEY (Kanalerzeugung, der
-- Multiplikator, der jeden per-Journey-Zaehler wertlos macht).
-- scope steht als eigene Spalte im PK, damit sich die Raeume nie beruehren.
CREATE TABLE attempt_throttle (
    scope VARCHAR(20) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    failed_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (scope, subject)
);

INSERT INTO attempt_throttle (scope, subject, failed_count, locked_until, updated_at)
SELECT 'ACCOUNT', CAST(account_id AS VARCHAR), failed_count, locked_until, updated_at
FROM login_attempt_throttle;

DROP TABLE login_attempt_throttle;

-- 2) DPoP-Replay-Schutz persistent ----------------------------------------
-- Vorher eine ConcurrentHashMap: nach jedem Neustart leer, pro Instanz
-- getrennt (hinter mehr als einer Replik also wirkungslos) und vom Angreifer
-- beliebig aufblasbar. Der PK IST die Replay-Pruefung: ein zweites INSERT
-- derselben (thumbprint, jti) verletzt ihn.
CREATE TABLE dpop_proof_replay (
    proof_key VARCHAR(255) PRIMARY KEY,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_dpop_proof_replay_expires_at ON dpop_proof_replay(expires_at);

-- 3) Freischaltcodes nicht mehr im Klartext --------------------------------
-- Ein FSC bleibt fachlich MEHRFACH einloesbar (kein used_at) - begrenzt wird
-- das Raten stattdessen durch den PERSON-Throttle oben, denn ein Treffer hier
-- ist eine volle Kontouebernahme (AdoptIdentity -> findOrCreateAccount).
-- Anders als bei den 6-stelligen TANs reicht ein ungepfefferter SHA-256: ein
-- FSC ist kein aufzaehlbarer Zahlenraum. Das haelt die Migration der
-- Bestandszeilen in SQL moeglich (H2 HASH()).
ALTER TABLE fsc_code ADD COLUMN code_hash VARCHAR(64);
UPDATE fsc_code SET code_hash = LOWER(RAWTOHEX(HASH('SHA-256', CAST(code AS VARBINARY))));
ALTER TABLE fsc_code ALTER COLUMN code_hash SET NOT NULL;
ALTER TABLE fsc_code DROP COLUMN code;
CREATE INDEX idx_fsc_code_person_id_code_hash ON fsc_code(person_id, code_hash);

-- 4) Attempt-scoped Geheimnisse der Ident-Module ebenfalls gehasht --------
-- id_fsc/id_eid hielten den eingegebenen FSC bzw. die eID-PIN bis zu 24 h
-- im Klartext, waehrend auth_sms/auth_email ihre TANs ausdruecklich nur
-- gehasht ablegen. Bestandszeilen sind attempt-scoped und binnen Minuten
-- wertlos - sie werden nicht konvertiert, sondern verworfen.
ALTER TABLE id_fsc_tool_data DROP COLUMN fsc;
ALTER TABLE id_fsc_tool_data ADD COLUMN fsc_hash VARCHAR(64);

ALTER TABLE id_eid_tool_data DROP COLUMN pin;
ALTER TABLE id_eid_tool_data ADD COLUMN pin_hash VARCHAR(64);

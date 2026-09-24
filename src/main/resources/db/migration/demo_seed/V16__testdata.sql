-- Demo persons with matching Freischaltcodes, so ident-fsc is playable right after startup
-- (docs/08-projektrahmen.md P-5/P-6). Codes: VALIDCODE, ERIKA123, JANE2026, PAULA2026.

-- Max, Erika and Jane are insured with us (Versicherungsnummer, KVNR); Paula is a Partner - known
-- to us by her Partnernummer only, not insured here (ADR-34), so her letter goes by that number.
INSERT INTO ext_personenverzeichnis.person (id, kvnr, versnr, name, vorname, geburtsdatum, strasse, hausnummer, plz, ort) VALUES
    ('P000000001', 'A123456789', '10000001', 'Muster',   'Max',   DATE '1985-06-15', 'Musterstraße', '1',  '12345', 'Musterstadt'),
    ('P000000002', 'B987654321', '10000002', 'Beispiel', 'Erika', DATE '1990-11-02', 'Beispielweg',  '42', '54321', 'Beispielhausen'),
    ('P000000003', 'C111111111', '10000003', 'Doe',      'Jane',  DATE '1978-03-30', 'Hauptstraße',  '7a', '10115', 'Berlin'),
    ('P000000004', NULL,         NULL,       'Schulz',   'Paula', DATE '1982-08-08', 'Lindenallee',  '3',  '20095', 'Hamburg');

-- Each code gets its letter, so the demo reads the plaintext from the register's mailbox (ADR-31).
INSERT INTO ext_personenverzeichnis.freischaltcode (person_id, code_hash, expires_at)
SELECT p.id, LOWER(RAWTOHEX(HASH('SHA-256', CAST(c.code AS VARBINARY)))), TIMESTAMP WITH TIME ZONE '2030-12-31 23:59:59+00:00'
  FROM ext_personenverzeichnis.person p
  JOIN (VALUES ('P000000001', 'VALIDCODE'), ('P000000002', 'ERIKA123'), ('P000000003', 'JANE2026'), ('P000000004', 'PAULA2026')) AS c (person_id, code)
    ON c.person_id = p.id;

INSERT INTO ext_personenverzeichnis.brief (person_id, freischaltcode_id, code, versandt_am)
SELECT p.id, f.id, c.code, CURRENT_TIMESTAMP
  FROM ext_personenverzeichnis.person p
  JOIN (VALUES ('P000000001', 'VALIDCODE'), ('P000000002', 'ERIKA123'), ('P000000003', 'JANE2026'), ('P000000004', 'PAULA2026')) AS c (person_id, code)
    ON c.person_id = p.id
  JOIN ext_personenverzeichnis.freischaltcode f
    ON f.person_id = p.id;

-- Demo persons with matching Freischaltcodes, so ident-fsc is playable right after startup
-- (docs/08-projektrahmen.md P-5/P-6). Codes: VALIDCODE, ERIKA123, JANE2026.

INSERT INTO ext_stammdaten.person (kvnr, name, vorname, geburtsdatum, strasse, hausnummer, plz, ort) VALUES
    ('A123456789', 'Muster',   'Max',   DATE '1985-06-15', 'Musterstraße', '1',  '12345', 'Musterstadt'),
    ('B987654321', 'Beispiel', 'Erika', DATE '1990-11-02', 'Beispielweg',  '42', '54321', 'Beispielhausen'),
    ('C111111111', 'Doe',      'Jane',  DATE '1978-03-30', 'Hauptstraße',  '7a', '10115', 'Berlin');

-- Each code gets its letter, so the demo reads the plaintext from the register's mailbox (ADR-31).
INSERT INTO ext_stammdaten.freischaltcode (person_id, code_hash, expires_at)
SELECT p.id, LOWER(RAWTOHEX(HASH('SHA-256', CAST(c.code AS VARBINARY)))), TIMESTAMP WITH TIME ZONE '2030-12-31 23:59:59+00:00'
  FROM ext_stammdaten.person p
  JOIN (VALUES ('A123456789', 'VALIDCODE'), ('B987654321', 'ERIKA123'), ('C111111111', 'JANE2026')) AS c (kvnr, code)
    ON c.kvnr = p.kvnr;

INSERT INTO ext_stammdaten.brief (person_id, freischaltcode_id, code, versandt_am)
SELECT p.id, f.id, c.code, CURRENT_TIMESTAMP
  FROM ext_stammdaten.person p
  JOIN (VALUES ('A123456789', 'VALIDCODE'), ('B987654321', 'ERIKA123'), ('C111111111', 'JANE2026')) AS c (kvnr, code)
    ON c.kvnr = p.kvnr
  JOIN ext_stammdaten.freischaltcode f
    ON f.person_id = p.id;

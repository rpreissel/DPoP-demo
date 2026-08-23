-- Test persons and matching FSC codes so the ident-fsc flow is directly
-- playable after startup (docs/08-projektrahmen.md P-5/P-6).

INSERT INTO person (kvnr, name, vorname, strasse, hausnummer, plz, ort) VALUES
    ('A123456789', 'Muster', 'Max', 'Musterstraße', '1', '12345', 'Musterstadt'),
    ('B987654321', 'Beispiel', 'Erika', 'Beispielweg', '42', '54321', 'Beispielhausen'),
    ('C111111111', 'Doe', 'Jane', 'Hauptstraße', '7a', '10115', 'Berlin');

INSERT INTO fsc_code (person_id, code, expires_at)
SELECT id, 'VALIDCODE', TIMESTAMP WITH TIME ZONE '2030-12-31T23:59:59Z'
FROM person WHERE kvnr = 'A123456789';

INSERT INTO fsc_code (person_id, code, expires_at)
SELECT id, 'ERIKA123', TIMESTAMP WITH TIME ZONE '2030-12-31T23:59:59Z'
FROM person WHERE kvnr = 'B987654321';

INSERT INTO fsc_code (person_id, code, expires_at)
SELECT id, 'JANE2026', TIMESTAMP WITH TIME ZONE '2030-12-31T23:59:59Z'
FROM person WHERE kvnr = 'C111111111';

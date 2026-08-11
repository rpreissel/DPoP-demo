INSERT INTO fsc_code (person_id, code, expires_at)
SELECT id, 'VALIDCODE', TIMESTAMP WITH TIME ZONE '2026-12-31T23:59:59Z'
FROM person WHERE kvnr = 'A123456789';

INSERT INTO fsc_code (person_id, code, expires_at)
SELECT id, 'ERIKA123', TIMESTAMP WITH TIME ZONE '2026-12-31T23:59:59Z'
FROM person WHERE kvnr = 'B987654321';

INSERT INTO fsc_code (person_id, code, expires_at)
SELECT id, 'JANE2026', TIMESTAMP WITH TIME ZONE '2026-12-31T23:59:59Z'
FROM person WHERE kvnr = 'C111111111';

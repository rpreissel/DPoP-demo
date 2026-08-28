-- ext_stammdaten: birthdate, needed so ident-eid's Ausweisdaten match has more than
-- name/vorname/address to check.

ALTER TABLE person ADD COLUMN geburtsdatum DATE;

UPDATE person SET geburtsdatum = DATE '1985-06-15' WHERE kvnr = 'A123456789';
UPDATE person SET geburtsdatum = DATE '1990-11-02' WHERE kvnr = 'B987654321';
UPDATE person SET geburtsdatum = DATE '1978-03-30' WHERE kvnr = 'C111111111';

-- id_eid module ---------------------------------------------------------

CREATE TABLE id_eid_tool_data (
    tool_session_id UUID PRIMARY KEY,
    kvnr VARCHAR(20),
    -- resolved by the orchestrator via ext_stammdaten and handed in, so id_eid never
    -- depends on the ext_stammdaten module directly (docs/08-projektrahmen.md #3: leaf
    -- modules stay decoupled from each other).
    person_id BIGINT,
    name VARCHAR(255),
    vorname VARCHAR(255),
    -- the simulated eID card's Ausweisdaten, read in the "card" step
    geburtsdatum DATE,
    strasse VARCHAR(255),
    hausnummer VARCHAR(20),
    plz VARCHAR(10),
    ort VARCHAR(255),
    pin VARCHAR(20),
    created_at TIMESTAMP NOT NULL
);

-- Schema des Moduls `id_fsc`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS id_fsc;

-- =============================================================================================
-- id_fsc
-- =============================================================================================

-- The Freischaltcodes themselves belong to the register (ext_personenverzeichnis.freischaltcode, ADR-31).
CREATE TABLE id_fsc.ident_tool_session (
    tool_session_id UUID PRIMARY KEY,
    kvnr            VARCHAR(20),
    -- Only without a KVNR (a Partner, ADR-34); the latest of the two identifiers wins.
    partnernr       VARCHAR(10),
    person_id       VARCHAR(10),
    name            VARCHAR(255),
    vorname         VARCHAR(255),
    geburtsdatum    DATE,
    fsc_hash        VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ident_tool_session_created_at ON id_fsc.ident_tool_session (created_at);

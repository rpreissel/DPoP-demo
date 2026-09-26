-- Schema des Moduls `id_eid`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS id_eid;

-- =============================================================================================
-- id_eid
-- =============================================================================================

CREATE TABLE id_eid.ident_tool_session (
    tool_session_id UUID PRIMARY KEY,
    family_name     VARCHAR(255),
    given_names     VARCHAR(255),
    birth_date      DATE,
    street_address  VARCHAR(255),
    postal_code     VARCHAR(10),
    locality        VARCHAR(255),
    restricted_id   VARCHAR(64),
    pin_hash        VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ident_tool_session_created_at ON id_eid.ident_tool_session (created_at);

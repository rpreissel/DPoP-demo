-- Schema des Moduls `id_kvnr`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS id_kvnr;

-- =============================================================================================
-- id_kvnr
-- =============================================================================================

CREATE TABLE id_kvnr.ident_tool_session (
    tool_session_id UUID PRIMARY KEY,
    kvnr            VARCHAR(20),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ident_tool_session_created_at ON id_kvnr.ident_tool_session (created_at);

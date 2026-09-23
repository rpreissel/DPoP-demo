-- Schema des Moduls `nect_mock` - das simulierte Fremdsystem Nect (docs/ideen/ident-nect.md).
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS nect_mock;

-- One identification case: opened by the relying party, finished on the jump page, redeemed once.
CREATE TABLE nect_mock.ident_case (
    id            UUID PRIMARY KEY,
    callback_uri  VARCHAR(500) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    procedure     VARCHAR(32),
    result        VARCHAR(4000),
    reason        VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at   TIMESTAMP WITH TIME ZONE,
    redeemed_at   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_ident_case_created_at ON nect_mock.ident_case (created_at);

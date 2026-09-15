-- account_anchor: the resolve-identity hot path of the claims model
-- (docs/ideen/claims-modell-und-vertrauensanker.md, "Dreiteilung statt einer Tabelle") - one
-- normalized, resolvable anchor value per row. UNIQUE(anchor_type, value) turns "which
-- account has this value?" into a lookup instead of a match; first writer wins, cross-account
-- conflicts stay upstream rejections (ADR-11, docs/12-entscheidungen.md), never silent
-- re-assignment. Provenance lives in account_attribute - this table is a projection
-- (rebuilt/re-bound from the log, no trust anchor of its own) and dies with its account,
-- same ownership rule as account_attribute (ON DELETE CASCADE).
CREATE TABLE account_anchor (
    id IDENTITY PRIMARY KEY,
    anchor_type VARCHAR(50) NOT NULL,
    anchor_value VARCHAR(255) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    established_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX ux_account_anchor ON account_anchor(anchor_type, anchor_value);
CREATE INDEX idx_account_anchor_account_id ON account_anchor(account_id);

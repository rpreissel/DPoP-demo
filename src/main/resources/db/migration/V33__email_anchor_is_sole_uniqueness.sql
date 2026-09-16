-- A3: one uniqueness authority for the confirmed e-mail, not two contradictory ones.
--
-- V6 put a UNIQUE index on the RAW account.email column; V31 put one on the NORMALIZED
-- account_anchor(anchor_type, anchor_value). Two spellings of the same address ("A@x.de" and
-- "a@x.de") therefore passed the column index but collided on the anchor - and the anchor write
-- used to swallow that collision, so the account ended up claiming an address whose anchor
-- pointed at a DIFFERENT account, with the two lookup paths disagreeing from then on.
--
-- account_anchor is the authority (it is the resolve direction of the claims model and holds the
-- canonical form); account.email stays a raw, non-unique projection column. Every read now goes
-- through the anchor (AccountService.findAccountByEmail/existsByEmail), so the column needs an
-- index only for diagnostics, no longer for correctness.

-- 1) Backfill anchors for confirmed e-mails that predate account_anchor. Idempotent (skips
--    anything already anchored) and collision-free: where several accounts normalize to the same
--    address, only the lowest account id - the first writer - gets the anchor, exactly as the
--    live write path would have decided. The remaining accounts keep their raw column value but
--    hold no anchor; they are the pre-existing conflicts this migration makes visible instead of
--    letting them keep diverging silently.
INSERT INTO account_anchor (anchor_type, anchor_value, account_id, established_at)
SELECT 'email', LOWER(TRIM(a.email)), MIN(a.id), COALESCE(MIN(a.email_confirmed_at), CURRENT_TIMESTAMP)
  FROM account a
 WHERE a.email IS NOT NULL
   AND TRIM(a.email) <> ''
   AND NOT EXISTS (
       SELECT 1 FROM account_anchor x
        WHERE x.anchor_type = 'email'
          AND x.anchor_value = LOWER(TRIM(a.email))
   )
 GROUP BY LOWER(TRIM(a.email));

-- 2) The raw column stops carrying a uniqueness claim it cannot honour consistently.
DROP INDEX idx_account_email;
CREATE INDEX idx_account_email ON account(email);

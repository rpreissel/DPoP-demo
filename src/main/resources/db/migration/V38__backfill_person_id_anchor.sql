-- docs/ideen/account-attribute-und-trust-vereinheitlichen.md, Paket 3 (anchor-migration):
-- PersonId and email now share one technical anchor path (tool_api/AttributeRules.kt). This
-- backfills account_anchor for PersonId from the account's own, already-authoritative
-- person_id column - the same additive, idempotent pattern V33 used for email.
--
-- No account_attribute changes: every real ident-fsc/ident-eid run already logged its PersonId
-- claim there via AccountService.recordClaim (PERSON_ID has always been provenance-logged, only
-- never anchored) - account_attribute already carries the genuine historical claims. This
-- migration only materializes the resolve-identity projection account_anchor was always meant
-- to hold, exactly like the value already sitting in account.person_id.
--
-- established_at is NOT a re-created proof timestamp - no original ident evidence exists to
-- backfill from at this layer, and inventing one would misrepresent when the person was actually
-- identified. It is set to the account's own created_at as the closest known, honest anchor: an
-- explicit technical backfill time, not a claim about the original evidence.
--
-- Collision-free without an account.id tie-break (unlike V33's e-mail case): account.person_id
-- has carried its own UNIQUE index since V32 (ux_account_person_id), so at most one account can
-- ever hold a given person_id - there is nothing to deduplicate here.
INSERT INTO account_anchor (anchor_type, anchor_value, account_id, established_at)
SELECT 'person_id', CAST(a.person_id AS VARCHAR), a.id, a.created_at
  FROM account a
 WHERE a.person_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM account_anchor x
        WHERE x.anchor_type = 'person_id'
          AND x.anchor_value = CAST(a.person_id AS VARCHAR)
   );

-- At most one active anchor value per (account, attribute type) - so far only enforced
-- application-side (AccountService.recordAnchor deletes any prior row for the same account+type
-- before inserting the new one); this makes it a DB-level guarantee too, closing the race a
-- concurrent double-write could otherwise slip through.
CREATE UNIQUE INDEX ux_account_anchor_account_type ON account_anchor(account_id, anchor_type);

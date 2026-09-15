-- account.person_id gets its DB-level uniqueness back: findOrCreateAccount is find-then-create,
-- so two parallel step-up channels resolving the same person could both pass the
-- findByPersonId check and insert duplicate accounts. The unique index turns that race into a
-- constraint violation instead (docs/ideen/claims-modell-und-vertrauensanker.md, "Schritt 0").
-- person_id is nullable since V28 (Enrollment first: Interessenten rows carry NULL); NULLs count
-- as distinct in H2 (the demo DB) as well as in PostgreSQL, so those rows never collide. H2 does
-- not support partial indexes - the PostgreSQL target picture expresses the same guarantee as a
-- partial index WHERE person_id IS NOT NULL. The old plain index is superseded by the unique one.
DROP INDEX idx_account_person_id;
CREATE UNIQUE INDEX ux_account_person_id ON account(person_id);

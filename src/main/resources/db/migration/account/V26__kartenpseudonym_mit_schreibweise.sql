-- Kartenpseudonyme (restricted_id) behalten im Claim-Log ihre Schreibweise, wie im Anker
-- (Review 2026-09, Phase F). Bisher wurden sie im Log kleingeschrieben: zwei verschiedene
-- Pseudonyme waren dort ein Wert.
UPDATE account.claim
SET normalized_value = TRIM(claim_value)
WHERE attribute_type IN ('restricted_id', 'nect_restricted_id');

-- Widerrufe tragen nur den normalisierten Wert; die Schreibweise holen sie sich vom Claim desselben
-- Kontos, den sie meinten.
UPDATE account.retraction r
SET normalized_value = (
    SELECT MIN(c.normalized_value) FROM account.claim c
    WHERE c.account_id = r.account_id AND c.attribute_type = r.attribute_type
      AND LOWER(c.normalized_value) = r.normalized_value
)
WHERE r.attribute_type IN ('restricted_id', 'nect_restricted_id')
  AND EXISTS (
    SELECT 1 FROM account.claim c
    WHERE c.account_id = r.account_id AND c.attribute_type = r.attribute_type
      AND LOWER(c.normalized_value) = r.normalized_value
  );

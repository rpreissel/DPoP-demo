-- Lets the journey log be queried by accountId, not just bindingKeyRef - the only lookup key a
-- WEB-channel entry (no DPoP binding key, see V20) has in common with an APP-channel entry once
-- an account is known on both.
ALTER TABLE journey_log ADD COLUMN account_id BIGINT;
CREATE INDEX idx_journey_log_account_id ON journey_log(account_id);

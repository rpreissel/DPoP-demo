-- Persistent device-to-account pairing (docs/02-domaenenmodell.md #3, corrected model):
-- independent of any single ChannelSession, so a known device can still jump straight to
-- LOGIN on a brand-new channel after logout or after the app lost its remembered session id.
CREATE TABLE device_account_link (
    binding_key_ref VARCHAR(64) PRIMARY KEY,
    account_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_device_account_link_account_id ON device_account_link(account_id);

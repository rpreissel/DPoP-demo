-- token_handle now holds a full JWT (mock AccessToken), not a short opaque handle.
ALTER TABLE auth_context ALTER COLUMN token_handle SET DATA TYPE VARCHAR(4096);
ALTER TABLE auth_context ADD COLUMN refresh_token_handle VARCHAR(255);

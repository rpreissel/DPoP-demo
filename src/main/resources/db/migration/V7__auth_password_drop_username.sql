-- auth_password no longer carries its own identifier - the account's confirmed email
-- (see V6) is the identifier now, enforced via ToolDescriptor.requiresConfirmedEmail.
ALTER TABLE auth_password DROP COLUMN username;

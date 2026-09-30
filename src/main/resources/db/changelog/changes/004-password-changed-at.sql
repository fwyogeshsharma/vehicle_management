--liquibase formatted sql
--
-- One column, so that a stateless token can be revoked.
--
-- The REST increment authenticates with a JWT bearer token and keeps no session table. That
-- buys a stateless API and costs revocation: a signed token stays valid until it expires, so
-- without something to check it against, changing a password would leave every token issued
-- before the change still working -- including the one held by whoever the change was meant to
-- lock out.
--
-- The authentication converter already loads the user on every request (to confirm the account
-- still exists and is active), so comparing the token's `iat` against this column is free. A
-- token issued before the last password change is rejected.
--
-- Nullable rather than NOT NULL DEFAULT now(): a null means "never changed", and a null
-- comparison must not reject a valid token. Existing rows keep their logins working.


--changeset vehiclemanagement:13-password-changed-at
--comment Lets a password change invalidate tokens issued before it
--rollback ALTER TABLE users DROP COLUMN password_changed_at;

ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;

COMMENT ON COLUMN users.password_changed_at IS
    'When the password hash was last set. A JWT whose iat predates this is rejected, which is '
    'how a stateless token is revoked. Null means it has never been changed since creation.';

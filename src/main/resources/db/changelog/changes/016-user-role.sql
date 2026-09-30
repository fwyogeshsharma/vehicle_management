--liquibase formatted sql
--
-- System access becomes its own column: ADMIN | CSR | TEJJJ_CSR.
--
-- This is the security increment the notes in `Roles.java` and CLAUDE.md said to wait for, and
-- the reason they said to wait is that adding a value is a changeset, so the whole set should
-- be decided at once. It now is.
--
-- **`role` is NOT a replacement for `user_type`, and must not become one.** They answer
-- different questions and both are load-bearing:
--
--   user_type  DRIVER | OWNER | BOTH | STAFF | ADMIN   what a person IS
--   role       ADMIN  | CSR   | TEJJJ_CSR             what a person may DO in this system
--
-- Most people in this table are drivers. A driver has no username, no password and now no
-- role, and `vehicle_x_user`, `LrService` and the whole vehicle model key off `user_type` to
-- know that. Folding the two columns together -- which is what the old ROLE_ + user_type
-- authority effectively did -- would leave a driver with no representation at all.
--
-- **A role exactly when there is a login.** `ck_users_role_pair` states it as an equality
-- between two booleans, the same shape `ck_intake_vehicle` and `ck_lr_cancelled` use, so
-- "can sign in but has no role" and "has a role but cannot sign in" are both unrepresentable
-- rather than merely discouraged. The first would have reached the authority converter with
-- nothing to grant.
--
-- **CSR and TEJJJ_CSR currently carry the same permissions.** Only ADMIN is guarded against
-- anywhere. The two are distinguished in the data so the distinction can be acted on when
-- somebody says what it should mean; inventing a difference here would be inventing a rule.

--changeset vehiclemanagement:29-user-role
--comment System access as its own column, separate from what a person is
--rollback ALTER TABLE users DROP COLUMN role;

ALTER TABLE users
    ADD COLUMN role VARCHAR(16);

-- Carry the existing accounts over before the constraint lands, or the CHECK fails on them.
-- An administrator stays one; anybody else who could sign in was office staff, which is what
-- CSR now means.
UPDATE users SET role = 'ADMIN' WHERE username IS NOT NULL AND user_type = 'ADMIN';
UPDATE users SET role = 'CSR'   WHERE username IS NOT NULL AND role IS NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_role CHECK (role IS NULL
                                        OR role IN ('ADMIN', 'CSR', 'TEJJJ_CSR')),
    ADD CONSTRAINT ck_users_role_pair CHECK ((role IS NOT NULL) = (username IS NOT NULL));

-- Every request resolves the caller's authority from this column, so it is read on every
-- single call. Partial, because the rows that have one are a small minority of the table.
CREATE INDEX idx_users_role ON users (role) WHERE role IS NOT NULL;

COMMENT ON COLUMN users.role IS
    'What the person may do in this system: ADMIN, CSR or TEJJJ_CSR. NULL for anyone without '
    'a login, which is most of the table. NOT the same as user_type, which says what they are '
    '(driver, owner, staff) and is what the vehicle model keys off.';

--liquibase formatted sql
--
-- A company's truck may only be driven by someone on that company's books.
--
-- Until now the two links between a company and a driver were independent: employment lived in
-- user_x_company, and "drives a company truck" was reachable only by going through
-- vehicles.owner_company_id. Nothing tied them, so a contract driver employed by nobody -- or
-- worse, a driver on a rival's payroll -- could be assigned to a company's vehicle.
--
-- This closes it in the database rather than in the service, for the same reason as every other
-- rule here: it has to hold against a native UPDATE, a data import and psql, not only against
-- calls that come through Java.
--
-- The mechanism is the composite-foreign-key chain used twice already (vehicle_x_location, and
-- the OWNER rows before 002 removed them). vehicle_x_user gains two mirror columns that cannot
-- drift, because foreign keys pin them:
--
--   is_company_owned  must equal the vehicle's own flag        (both NOT NULL -> never skipped)
--   owner_company_id  must be present exactly when that is true (a CHECK), and must be the
--                     vehicle's actual owner, and must name a company the driver works for
--
-- Together those make the rule unrepresentable rather than merely discouraged. An owner-operator
-- is untouched: his vehicle is person-owned, so is_company_owned is false, owner_company_id is
-- null, and both company foreign keys skip themselves under MATCH SIMPLE.


--changeset vehiclemanagement:12-company-drivers-only
--comment A company vehicle may only be driven by someone employed by that company
--rollback ALTER TABLE vehicle_x_user DROP CONSTRAINT fk_vxu_employed;
--rollback ALTER TABLE vehicle_x_user DROP CONSTRAINT fk_vxu_vehicle_company;
--rollback ALTER TABLE vehicle_x_user DROP CONSTRAINT fk_vxu_vehicle_owned;
--rollback ALTER TABLE vehicle_x_user DROP CONSTRAINT ck_vxu_company_present;
--rollback ALTER TABLE vehicle_x_user DROP COLUMN owner_company_id;
--rollback ALTER TABLE vehicle_x_user DROP COLUMN is_company_owned;
--rollback ALTER TABLE vehicles DROP CONSTRAINT uq_vehicles_id_company;

-- The FK target for "this row names the vehicle's real owning company".
ALTER TABLE vehicles ADD CONSTRAINT uq_vehicles_id_company UNIQUE (id, owner_company_id);

ALTER TABLE vehicle_x_user ADD COLUMN owner_company_id BIGINT;
ALTER TABLE vehicle_x_user ADD COLUMN is_company_owned BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill from the vehicle before any constraint exists, or creating them would fail on rows
-- that are perfectly legitimate.
UPDATE vehicle_x_user vu
   SET owner_company_id = v.owner_company_id,
       is_company_owned = v.is_company_owned
  FROM vehicles v
 WHERE v.id = vu.vehicle_id;

-- **This deletes data.** Any existing assignment of a driver to a company vehicle where that
-- driver is not on the company's books breaks the new rule and cannot be kept. The alternative --
-- inventing an employment record to make the row legal -- would be worse: it would assert an
-- employment relationship nobody entered. These deletions are NOT restored by the rollback,
-- because the rollback only removes constraints; it cannot know which rows it took out.
DELETE FROM vehicle_x_user vu
 WHERE vu.is_company_owned
   AND NOT EXISTS (SELECT 1 FROM user_x_company uc
                    WHERE uc.user_id = vu.user_id
                      AND uc.company_id = vu.owner_company_id);

-- 1. The flag must match the vehicle. Both columns are NOT NULL, so MATCH SIMPLE never skips
--    this one -- it is what stops a row claiming to be person-owned to dodge the rules below.
--    ON UPDATE NO ACTION: selling the vehicle is refused while driver rows exist, which is why
--    VehicleService.setOwner clears them first.
ALTER TABLE vehicle_x_user ADD CONSTRAINT fk_vxu_vehicle_owned
    FOREIGN KEY (vehicle_id, is_company_owned)
    REFERENCES vehicles (id, is_company_owned) ON DELETE CASCADE ON UPDATE NO ACTION;

-- 2. A company id is present exactly when the vehicle is company-owned.
ALTER TABLE vehicle_x_user ADD CONSTRAINT ck_vxu_company_present
    CHECK (is_company_owned = (owner_company_id IS NOT NULL));

-- 3. And it is the vehicle's actual owner, not some other company.
ALTER TABLE vehicle_x_user ADD CONSTRAINT fk_vxu_vehicle_company
    FOREIGN KEY (vehicle_id, owner_company_id)
    REFERENCES vehicles (id, owner_company_id) ON DELETE CASCADE ON UPDATE NO ACTION;

-- 4. And the driver is on that company's books. CASCADE rather than RESTRICT: when someone
--    leaves a company they stop driving its trucks, which is both the intent and the only way to
--    keep the rule true without a manual step.
ALTER TABLE vehicle_x_user ADD CONSTRAINT fk_vxu_employed
    FOREIGN KEY (user_id, owner_company_id)
    REFERENCES user_x_company (user_id, company_id) ON DELETE CASCADE;

CREATE INDEX idx_vxu_company ON vehicle_x_user (owner_company_id);

COMMENT ON TABLE vehicle_x_user IS
    'Who drives a vehicle. Ownership is vehicles.owner_company_id / owner_user_id -- an '
    'owner-operator appears in both, once for each fact. A company vehicle may only be driven '
    'by someone in user_x_company for that company; the mirror columns here are pinned to the '
    'vehicle by foreign keys and are never set by hand.';

--liquibase formatted sql
--
-- vehicle_x_user becomes drivers-only.
--
-- It used to hold two kinds of row, told apart by `role`. The DRIVER rows carry real
-- information: who drives a truck, which is not derivable from anywhere else and is a
-- many-to-one a vehicle row cannot hold. The OWNER rows carried none -- they were a copy of
-- vehicles.owner_user_id, and fk_vxu_owner_matches existed solely to stop the copy drifting.
--
-- They were there because the original design put ownership in this table. Ownership then moved
-- onto the vehicle row, because "exactly one owner, a company or a person" is a single-row
-- invariant and only a single-row CHECK can enforce it against every writer. At that point the
-- OWNER rows became redundant, and the response was to police them rather than remove them.
-- This removes them.
--
-- What is NOT lost, and is the case this system exists for: an owner-operator who owns his truck
-- and drives it. He is still recorded twice, once for each fact --
--   vehicles.owner_user_id = him   (he owns it)
--   vehicle_x_user         = him   (he drives it)
-- What goes is the third row that said he owns it a second time.
--
-- After this: vehicles answers "who owns this", vehicle_x_user answers "who drives this", for
-- company trucks and owner-operators alike. One home per fact.


--changeset vehiclemanagement:11-drop-owner-rows
--comment Remove the OWNER rows; they duplicate vehicles.owner_user_id
--rollback ALTER TABLE vehicle_x_user ADD COLUMN role VARCHAR(8) NOT NULL DEFAULT 'DRIVER';
--rollback ALTER TABLE vehicle_x_user ALTER COLUMN role DROP DEFAULT;
--rollback ALTER TABLE vehicle_x_user ADD CONSTRAINT ck_vxu_role CHECK (role IN ('OWNER','DRIVER'));
--rollback ALTER TABLE vehicle_x_user DROP CONSTRAINT pk_vehicle_x_user;
--rollback ALTER TABLE vehicle_x_user ADD CONSTRAINT pk_vehicle_x_user PRIMARY KEY (vehicle_id, user_id, role);
--rollback ALTER TABLE vehicle_x_user ADD COLUMN owner_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN role = 'OWNER' THEN user_id END) STORED;
--rollback ALTER TABLE vehicles ADD CONSTRAINT uq_vehicles_id_owner_user UNIQUE (id, owner_user_id);
--rollback ALTER TABLE vehicle_x_user ADD CONSTRAINT fk_vxu_owner_matches FOREIGN KEY (vehicle_id, owner_user_id) REFERENCES vehicles (id, owner_user_id) ON DELETE CASCADE ON UPDATE NO ACTION;
--rollback DROP INDEX uq_vxu_primary_driver;
--rollback CREATE UNIQUE INDEX uq_vxu_primary_driver ON vehicle_x_user (vehicle_id) WHERE role = 'DRIVER' AND is_primary;
--rollback INSERT INTO vehicle_x_user (vehicle_id, user_id, role) SELECT id, owner_user_id, 'OWNER' FROM vehicles WHERE owner_user_id IS NOT NULL ON CONFLICT DO NOTHING;

-- Order matters. The OWNER rows must go first: once `role` leaves the primary key, an
-- owner-operator holding both an OWNER and a DRIVER row for one vehicle would be a duplicate
-- key. Deleting them is lossless -- every one is reconstructable from vehicles.owner_user_id,
-- which is exactly what the rollback above does.
DELETE FROM vehicle_x_user WHERE role = 'OWNER';

-- The foreign key, the generated column it read, and the unique constraint it pointed at all
-- existed only to keep the OWNER rows honest.
ALTER TABLE vehicle_x_user DROP CONSTRAINT fk_vxu_owner_matches;
ALTER TABLE vehicle_x_user DROP COLUMN owner_user_id;
ALTER TABLE vehicles        DROP CONSTRAINT uq_vehicles_id_owner_user;

-- A vehicle/person pair is now the whole key: one row says "this person drives this vehicle".
ALTER TABLE vehicle_x_user DROP CONSTRAINT pk_vehicle_x_user;
ALTER TABLE vehicle_x_user DROP CONSTRAINT ck_vxu_role;
ALTER TABLE vehicle_x_user DROP COLUMN role;
ALTER TABLE vehicle_x_user ADD CONSTRAINT pk_vehicle_x_user PRIMARY KEY (vehicle_id, user_id);

-- Still at most one primary driver per vehicle; the role half of the predicate is now implied.
-- IF EXISTS because PostgreSQL has already dropped this index itself: a partial index whose
-- predicate mentions a column goes automatically when that column does.
DROP INDEX IF EXISTS uq_vxu_primary_driver;
CREATE UNIQUE INDEX uq_vxu_primary_driver ON vehicle_x_user (vehicle_id) WHERE is_primary;

COMMENT ON TABLE vehicle_x_user IS
    'Who drives a vehicle. Ownership is vehicles.owner_company_id / owner_user_id -- an '
    'owner-operator appears in both, once for each fact.';

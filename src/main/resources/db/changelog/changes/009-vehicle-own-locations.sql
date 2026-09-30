--liquibase formatted sql
--
-- A company's truck may now have locations of its own.
--
-- **This reverses a rule the schema went to some trouble to enforce**, so it is worth recording
-- what was there and why it is going.
--
-- The original model said: a vehicle's own preferred locations may exist only while no company
-- owns it. A company's trucks inherited the company's route and could hold nothing themselves.
-- That was enforced three ways at once — a `ck_vxl_not_company_owned` CHECK, an
-- `is_company_owned` discriminator column that existed for no other purpose, and a composite FK
-- to `vehicles (id, is_company_owned)` with ON UPDATE NO ACTION so that selling a vehicle to a
-- company while it had its own rows was *rejected* rather than silently cleaned up.
--
-- It was a coherent model and the enforcement was sound. It was also wrong about the business:
-- a haulier whose fleet runs Maharashtra generally will still have one truck dedicated to a
-- Nagpur–Pune shuttle, and under the old rule the only way to say so was to give the whole
-- company that route. The rule made the common case unsayable and the workaround wrong.
--
-- So the two are now additive: a vehicle's effective locations are its company's **plus** its
-- own. Neither replaces the other, and `vehicle_effective_locations` says which is which through
-- its `source` column, because "this truck goes to Nagpur specifically" and "this firm covers
-- Maharashtra" are different facts and a dispatcher may want to know which one matched.
--
-- What is NOT changing: `company_x_location` still belongs to the company and every truck it
-- owns still inherits it. Setting a company's locations is still a change to its whole fleet.


--changeset vehiclemanagement:19-vehicle-own-locations splitStatements:false
--comment A company-owned vehicle may hold its own locations, additive to the company's
--rollback DROP VIEW vehicle_effective_locations;
--rollback DELETE FROM vehicle_x_location l USING vehicles v
--rollback  WHERE v.id = l.vehicle_id AND v.owner_company_id IS NOT NULL;
--rollback ALTER TABLE vehicle_x_location DROP CONSTRAINT fk_vxl_vehicle;
--rollback ALTER TABLE vehicle_x_location ADD COLUMN is_company_owned BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE vehicle_x_location ADD CONSTRAINT ck_vxl_not_company_owned CHECK (is_company_owned = FALSE);
--rollback ALTER TABLE vehicle_x_location ADD CONSTRAINT fk_vxl_vehicle FOREIGN KEY (vehicle_id, is_company_owned) REFERENCES vehicles (id, is_company_owned) ON DELETE CASCADE ON UPDATE NO ACTION;
--rollback CREATE VIEW vehicle_effective_locations AS
--rollback SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'COMPANY' AS source FROM vehicles v JOIN company_x_location l ON l.company_id = v.owner_company_id WHERE v.owner_company_id IS NOT NULL
--rollback UNION ALL
--rollback SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'VEHICLE' AS source FROM vehicles v JOIN vehicle_x_location l ON l.vehicle_id = v.id WHERE v.owner_company_id IS NULL;

-- The discriminator and the two constraints that used it go together: the column existed only so
-- the composite FK had something to compare, and the FK existed only to enforce the CHECK.
-- Dropping the column drops the CHECK with it, so the FK must go first.
ALTER TABLE vehicle_x_location DROP CONSTRAINT fk_vxl_vehicle;
ALTER TABLE vehicle_x_location DROP COLUMN is_company_owned;

-- A plain FK does what is still wanted: the rows belong to the vehicle and die with it.
ALTER TABLE vehicle_x_location
    ADD CONSTRAINT fk_vxl_vehicle FOREIGN KEY (vehicle_id)
        REFERENCES vehicles (id) ON DELETE CASCADE;

-- Additive. The second branch has lost its `WHERE v.owner_company_id IS NULL`, which is the
-- whole change: a vehicle's own rows count whoever owns it.
--
-- UNION ALL, not UNION, so a truck that names Nagpur while its company also names Nagpur still
-- reports both — with different `source` values, which is a real distinction. Callers that want
-- a list of places rather than a list of claims must DISTINCT on (state_id, city_id); the
-- summary in VehicleRepository.locationsFor does exactly that.
CREATE OR REPLACE VIEW vehicle_effective_locations AS
SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'COMPANY' AS source
  FROM vehicles v
  JOIN company_x_location l ON l.company_id = v.owner_company_id
 WHERE v.owner_company_id IS NOT NULL
UNION ALL
SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'VEHICLE' AS source
  FROM vehicles v
  JOIN vehicle_x_location l ON l.vehicle_id = v.id;

COMMENT ON TABLE vehicle_x_location IS
    'A vehicle''s OWN locations. Additive to its company''s, not an alternative to them: read '
    'vehicle_effective_locations for the resolved set, which marks each row COMPANY or VEHICLE.';

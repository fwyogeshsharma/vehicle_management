--liquibase formatted sql
--
-- What a driver quotes to run a truck to a place.
--
-- On the call the CSR learns where the truck goes and what the driver wants for each place.
-- The place is already a vehicle_x_location row; the quote is a fact about that same row, so it
-- is a column on it rather than a table of its own.
--
-- **The price is a TOTAL for the run, as quoted.** Not per tonne, and nothing is derived from it.
-- Nullable, because most places are recorded before anyone has asked.
--
-- **Only the vehicle's own locations carry one.** company_x_location is shared by every truck a
-- company owns, so one driver's quote has no honest home there. The view returns NULL for those
-- rows, which keeps the two sources in one shape.
--
-- **One price per place, overwritten when it changes.** The history of what a driver asked in
-- March is not kept; that was a deliberate choice, not an oversight.

--changeset vehiclemanagement:35-vehicle-location-price
--comment vehicle_x_location.price: the driver's total quote for running to that place
--rollback DROP VIEW vehicle_effective_locations;
--rollback CREATE VIEW vehicle_effective_locations AS SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'COMPANY' AS source FROM vehicles v JOIN company_x_location l ON l.company_id = v.owner_company_id WHERE v.owner_company_id IS NOT NULL UNION ALL SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'VEHICLE' AS source FROM vehicles v JOIN vehicle_x_location l ON l.vehicle_id = v.id;
--rollback ALTER TABLE vehicle_x_location DROP CONSTRAINT ck_vxl_price;
--rollback ALTER TABLE vehicle_x_location DROP COLUMN price;

ALTER TABLE vehicle_x_location
    ADD COLUMN price NUMERIC(12,2);

ALTER TABLE vehicle_x_location
    ADD CONSTRAINT ck_vxl_price CHECK (price IS NULL OR price >= 0);

COMMENT ON COLUMN vehicle_x_location.price IS
    'The driver''s quoted TOTAL for running this vehicle to this place, in rupees. Null = not asked.';

-- Appending a column at the end is the one change CREATE OR REPLACE VIEW allows.
CREATE OR REPLACE VIEW vehicle_effective_locations AS
SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'COMPANY' AS source, NULL::numeric(12,2) AS price
  FROM vehicles v
  JOIN company_x_location l ON l.company_id = v.owner_company_id
 WHERE v.owner_company_id IS NOT NULL
UNION ALL
SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'VEHICLE' AS source, l.price
  FROM vehicles v
  JOIN vehicle_x_location l ON l.vehicle_id = v.id;

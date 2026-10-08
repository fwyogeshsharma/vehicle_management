--liquibase formatted sql
--
-- Which account put a vehicle on the register.
--
-- Set by VehicleService.create, which every creation path goes through: the Add vehicle form
-- (POST /api/vehicles), the desk intake (POST /api/vehicles/intake) and completing a photo
-- intake (POST /api/intake/{id}/complete). The id comes from the caller's bearer token.
--
-- Nullable: vehicles from before this changeset, the seeders and imports have no account.
-- ON DELETE SET NULL, as vehicle_intake.uploaded_by (025): removing a person must not be
-- refused because they once registered a truck.

--changeset vehiclemanagement:26-vehicle-added-by
--comment vehicles.added_by: the signed-in account that registered the vehicle
--rollback ALTER TABLE vehicles DROP COLUMN added_by;

ALTER TABLE vehicles
    ADD COLUMN added_by BIGINT,
    ADD CONSTRAINT fk_vehicles_added_by FOREIGN KEY (added_by) REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN vehicles.added_by IS
    'users.id of the signed-in account that registered the vehicle. NULL before changeset 026, and for seeded or imported rows.';

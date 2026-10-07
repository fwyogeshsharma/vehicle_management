--liquibase formatted sql
--
-- A vehicle may now be registered without a registration number or a body type.
--
-- 001 made both NOT NULL on the grounds that a truck on the register always has them. The desk
-- does not: a CSR on the phone often has the driver and the company but not the plate, and the
-- body type is a guess until someone sees the truck. Refusing the vehicle until both are known
-- left it sitting in the intake worklist with nowhere to go.
--
-- What still holds when they ARE given:
--   - `uq_vehicles_reg` keeps a plate unique. It is NULLS DISTINCT (the default), so any number
--     of vehicles may have no plate, which is the point.
--   - `ck_vehicles_reg_canon` still insists on the canonical spelling; a NULL passes a CHECK.
--   - `fk_vehicles_body_type` still requires a real body type; a NULL skips the FK.
--
-- The rollback fails if any vehicle has been saved without either. That is deliberate: putting
-- the NOT NULL back means first deciding what those vehicles' plates and body types are.

--changeset vehiclemanagement:23-vehicle-registration-body-type-optional
--comment Allow vehicles with no registration number or body type
--rollback ALTER TABLE vehicles ALTER COLUMN registration_number SET NOT NULL, ALTER COLUMN body_type_id SET NOT NULL;

ALTER TABLE vehicles
    ALTER COLUMN registration_number DROP NOT NULL,
    ALTER COLUMN body_type_id DROP NOT NULL;

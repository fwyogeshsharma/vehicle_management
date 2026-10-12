--liquibase formatted sql
--
-- Whether someone has checked a truck's details with a person, rather than taking them on
-- trust from a photo, an import or a form.
--
-- On both tables. On vehicle_intake it is the CSR's answer while working the row -- saved with
-- a correction, so it survives closing the form -- and completing copies it onto the vehicle.
-- On vehicles it is the lasting fact, and it can be set or cleared from the edit form.
--
-- This service owns vehicle_intake.is_verified; the OCR worker never reads or writes it.
-- NOT NULL DEFAULT false: every existing row is unverified until someone says otherwise.

--changeset vehiclemanagement:27-verified
--comment vehicles.is_verified and vehicle_intake.is_verified: details confirmed by a person
--rollback ALTER TABLE vehicle_intake DROP COLUMN is_verified; ALTER TABLE vehicles DROP COLUMN is_verified;

ALTER TABLE vehicles
    ADD COLUMN is_verified BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE vehicle_intake
    ADD COLUMN is_verified BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN vehicles.is_verified IS
    'True once a person has confirmed the details. Set from the edit form, or carried over from a verified photo intake.';
COMMENT ON COLUMN vehicle_intake.is_verified IS
    'The CSR''s verified tick while working the row; copied onto the vehicle at completion. Owned by vehicleManagement, not the OCR worker.';

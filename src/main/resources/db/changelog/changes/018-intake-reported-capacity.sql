--liquibase formatted sql
--
-- The capacity the mobile app reports, as it spells it.
--
-- Free text, like reported_body_type, and for the same reason: the app owns its pick list and
-- a mismatch must not cost us the photo. The CSR resolves it into edited_capacity on the call,
-- and THAT column is what feeds vehicles.capacity.

--changeset vehiclemanagement:18-intake-reported-capacity
--comment Capacity as the mobile app reports it
--rollback ALTER TABLE vehicle_intake DROP COLUMN reported_capacity;

ALTER TABLE vehicle_intake
    ADD COLUMN reported_capacity VARCHAR(32);

COMMENT ON COLUMN vehicle_intake.reported_capacity IS
    'Capacity as the mobile app spells it, free text. The CSR confirms it into edited_capacity.';

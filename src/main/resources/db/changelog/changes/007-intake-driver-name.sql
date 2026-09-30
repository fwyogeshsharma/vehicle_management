--liquibase formatted sql
--
-- The driver's name, on the intake row.
--
-- The one field on the completion form that OCR can never supply: a name is not painted on the
-- side of a truck. It comes from the phone call, and until now there was nowhere to put it until
-- the CSR was ready to create the vehicle in one go.
--
-- That made the worklist lopsided. A CSR rings a driver, learns the name, gets told to call back
-- in an hour — and had either to hold the name in their head, or open the completion form and
-- leave it half-filled where nothing is saved. Every other value on the row could be corrected
-- and kept; this one could not.
--
-- Named `edited_` with the corrections rather than `reported_`/`ocr_` because it belongs to the
-- same author as they do: the CSR. Nothing else can write it.


--changeset vehiclemanagement:16-intake-driver-name
--comment The driver's name, learned on the call and kept before completion
--rollback ALTER TABLE vehicle_intake DROP COLUMN edited_driver_name;

ALTER TABLE vehicle_intake
    ADD COLUMN edited_driver_name VARCHAR(128);

COMMENT ON COLUMN vehicle_intake.edited_driver_name IS
    'The driver''s name as the CSR took it down. No ocr_ or reported_ counterpart: OCR cannot '
    'read a name off a truck, and the field app does not ask for one.';

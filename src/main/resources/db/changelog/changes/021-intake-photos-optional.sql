--liquibase formatted sql
--
-- A report may now arrive with no photos.
--
-- `ck_intake_has_images` (005) refused an empty `image_keys`. The field app can report a truck
-- from what the executive typed alone, so the rule was wrong about the business. `image_keys`
-- stays NOT NULL -- an empty array is the "no photos" value, never a null.
--
-- A row with no photos must not be QUEUED: the OCR worker would claim it and have nothing to
-- read. The API inserts such a row as DONE, which is how "OCR ran and read nothing" is already
-- represented, so it lands in the CSR worklist.

--changeset vehiclemanagement:21-intake-photos-optional
--comment Allow vehicle_intake rows with an empty image_keys array
--rollback ALTER TABLE vehicle_intake ADD CONSTRAINT ck_intake_has_images CHECK (jsonb_array_length(image_keys) > 0);

ALTER TABLE vehicle_intake DROP CONSTRAINT ck_intake_has_images;

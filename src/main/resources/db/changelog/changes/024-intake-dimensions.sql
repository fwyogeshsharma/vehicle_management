--liquibase formatted sql
--
-- The CSR's axles, wheels and length on a pending intake.
--
-- The worklist form has always had these boxes, and "Save" sent them, but vehicle_intake had
-- nowhere to put them: the API ignored the fields and the form reopened blank. A CSR who took
-- them down on the call lost them unless they completed the vehicle in the same sitting.
--
-- The edited_ prefix and NULL-means-uncorrected follow the other CSR columns. There is no
-- ocr_ counterpart, and only wheels has a reported_ one (reported_no_of_wheels, 010), which the
-- summary falls back to.
--
-- The CHECKs are the same physical bounds as on vehicles (001), so a value that saves here can
-- also be completed. This service is the only writer of edited_ columns; the OCR worker does not
-- read or write them, so nothing is needed on its side.

--changeset vehiclemanagement:24-intake-dimensions
--comment CSR's axles, wheels and length on vehicle_intake
--rollback ALTER TABLE vehicle_intake DROP COLUMN edited_length_ft, DROP COLUMN edited_no_of_wheels, DROP COLUMN edited_no_of_axles;

ALTER TABLE vehicle_intake
    ADD COLUMN edited_no_of_axles  SMALLINT,
    ADD COLUMN edited_no_of_wheels SMALLINT,
    ADD COLUMN edited_length_ft    NUMERIC(5,2),
    ADD CONSTRAINT ck_intake_edited_axles
        CHECK (edited_no_of_axles IS NULL OR edited_no_of_axles BETWEEN 1 AND 12),
    -- mod(), not the modulo operator: see 001.
    ADD CONSTRAINT ck_intake_edited_wheels
        CHECK (edited_no_of_wheels IS NULL
               OR (edited_no_of_wheels BETWEEN 2 AND 32 AND mod(edited_no_of_wheels, 2) = 0)),
    ADD CONSTRAINT ck_intake_edited_length
        CHECK (edited_length_ft IS NULL OR edited_length_ft BETWEEN 4 AND 80);

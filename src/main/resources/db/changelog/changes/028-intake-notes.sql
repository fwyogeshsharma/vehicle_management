--liquibase formatted sql
--
-- The CSR's free-text remarks on a photo intake, typed while working the row -- "driver asked
-- to call after 6pm", "second truck with the same owner". Saved with a correction, so a note
-- survives closing the form, and copied onto vehicles.notes when the intake is completed.
--
-- The same width as vehicles.notes, so a note that fits here always fits there.
-- This service owns the column; the OCR worker never reads or writes it.
-- Not review_note: that is the reason a row was discarded, a different fact.

--changeset vehiclemanagement:28-intake-notes
--comment vehicle_intake.notes: the CSR's remarks, copied onto the vehicle at completion
--rollback ALTER TABLE vehicle_intake DROP COLUMN notes;

ALTER TABLE vehicle_intake
    ADD COLUMN notes VARCHAR(2000);

COMMENT ON COLUMN vehicle_intake.notes IS
    'The CSR''s free-text remarks while working the row; copied onto vehicles.notes at completion. Owned by vehicleManagement, not the OCR worker.';

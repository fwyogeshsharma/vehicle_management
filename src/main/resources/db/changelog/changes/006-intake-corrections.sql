--liquibase formatted sql
--
-- What the CSR says the photo actually says.
--
-- A third opinion on the same truck, and deliberately its own set of columns rather than an
-- overwrite of the ocr_* ones.
--
-- **Why not just correct ocr_plate in place.** Measured on real field photos, OCR returned
-- WC32KN7996 for a truck whose painted plate reads UP32 KN 7996: the digits right, the state
-- code wrong, and the result a perfectly plausible registration that belongs to nobody. That
-- class of error is only ever caught by someone comparing the read against the picture — and
-- only ever *studied* if the original read survives the correction. Overwrite the column and
-- the evidence that the engine has a systematic problem with hand-painted state codes is gone,
-- one helpful correction at a time.
--
-- So: three sources of truth, none of them overwriting another.
--
--   reported_*   what the field executive typed     a claim
--   ocr_*        what the machine read              a suggestion, immutable
--   edited_*     what the CSR says it is            the one that wins
--
-- Everything downstream reads edited_* first and falls back to ocr_*, then reported_*.


--changeset vehiclemanagement:15-intake-corrections
--comment A CSR's corrections to what OCR read, kept apart from the reads themselves
--rollback ALTER TABLE vehicle_intake DROP COLUMN edited_plate,
--rollback                            DROP COLUMN edited_mobiles,
--rollback                            DROP COLUMN edited_company,
--rollback                            DROP COLUMN edited_by,
--rollback                            DROP COLUMN edited_at;

ALTER TABLE vehicle_intake
    -- All nullable, and null is meaningful: it means "nobody has corrected this", which is not
    -- the same as "the CSR blanked it". A correction to empty is stored as an empty string for
    -- the text columns and an empty array for the list.
    ADD COLUMN edited_plate   VARCHAR(32),
    ADD COLUMN edited_mobiles JSONB,
    ADD COLUMN edited_company VARCHAR(160),
    ADD COLUMN edited_by      VARCHAR(128),
    ADD COLUMN edited_at      TIMESTAMPTZ;

COMMENT ON COLUMN vehicle_intake.edited_plate IS
    'What the CSR says the plate is. Wins over ocr_plate, which is left as the machine read it.';
COMMENT ON COLUMN vehicle_intake.edited_mobiles IS
    'What the CSR says the numbers are. Wins over ocr_mobiles. NULL means uncorrected; [] means '
    'the CSR says there are none.';

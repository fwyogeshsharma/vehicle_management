--liquibase formatted sql
--
-- The body_types row the reported text matched, if any.
--
-- reported_body_type stays the app's free text. This is the same value resolved against the
-- master at upload time, so the UI can pre-select the dropdown. It is NOT a CSR decision:
-- edited_body_type_id still wins, and null here means "no master row had that name".

--changeset vehiclemanagement:19-intake-matched-body-type
--comment Master body type matched from the reported text at upload
--rollback ALTER TABLE vehicle_intake DROP COLUMN matched_body_type_id;

ALTER TABLE vehicle_intake
    ADD COLUMN matched_body_type_id BIGINT;

ALTER TABLE vehicle_intake
    ADD CONSTRAINT fk_intake_matched_body_type
        FOREIGN KEY (matched_body_type_id) REFERENCES body_types (id) ON DELETE RESTRICT;

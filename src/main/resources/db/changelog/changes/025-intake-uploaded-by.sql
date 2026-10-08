--liquibase formatted sql
--
-- Which account uploaded the report, as a key rather than a name.
--
-- `reported_by` is a display string: the uploader's name at upload time, or for older rows
-- whatever the app typed. It cannot be joined, and it goes stale when a person is renamed.
-- Since uploads require a bearer token, the uploader is known exactly; this records the id
-- taken from that token.
--
-- Nullable: rows from before this changeset have no account, and neither did FreightDesk's.
-- ON DELETE SET NULL, as lorry_receipts.driver_user_id: removing a person must not be refused
-- because they once photographed a truck, and must not take the report with them.
-- Owned by this service. The OCR worker neither reads nor writes it.

--changeset vehiclemanagement:25-intake-uploaded-by
--comment vehicle_intake.uploaded_by: the signed-in account that uploaded the report
--rollback ALTER TABLE vehicle_intake DROP COLUMN uploaded_by;

ALTER TABLE vehicle_intake
    ADD COLUMN uploaded_by BIGINT,
    ADD CONSTRAINT fk_vi_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN vehicle_intake.uploaded_by IS
    'users.id of the signed-in account that uploaded the report, from its token. NULL for rows before changeset 025.';

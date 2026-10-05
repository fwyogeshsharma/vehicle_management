--liquibase formatted sql
--
-- One body type and one capacity on the intake row, each an id.
--
-- Until now each lived in several columns: the app's text (reported_*), the master row it
-- matched (matched_body_type_id) and the CSR's pick (edited_*). The mobile report now sends the
-- ids directly and the CSR overwrites them, so a single column each says everything the three
-- did. Existing rows are carried across: the CSR's value wins, else what the app reported.
--
-- Capacity was free text, so it is mapped to a capacities row by label; text matching no label
-- becomes null rather than failing the migration.
--
-- This is a NEW changeset rather than an edit to 008 / 010 / 018 / 019: those have already run,
-- and Liquibase refuses to start when a checksum changes.


--changeset vehiclemanagement:32-intake-single-body-type-capacity
--comment Collapse reported/matched/edited body type and capacity into body_type_id and capacity_id
--rollback ALTER TABLE vehicle_intake
--rollback     ADD COLUMN edited_body_type_id   BIGINT REFERENCES body_types (id),
--rollback     ADD COLUMN edited_capacity       VARCHAR(32),
--rollback     ADD COLUMN reported_body_type    VARCHAR(64),
--rollback     ADD COLUMN reported_capacity     VARCHAR(32),
--rollback     ADD COLUMN matched_body_type_id  BIGINT;
--rollback ALTER TABLE vehicle_intake ADD CONSTRAINT fk_intake_matched_body_type
--rollback     FOREIGN KEY (matched_body_type_id) REFERENCES body_types (id) ON DELETE RESTRICT;
--rollback UPDATE vehicle_intake SET edited_body_type_id = body_type_id,
--rollback     edited_capacity = (SELECT label FROM capacities WHERE id = capacity_id);
--rollback ALTER TABLE vehicle_intake DROP COLUMN body_type_id, DROP COLUMN capacity_id;

ALTER TABLE vehicle_intake
    ADD COLUMN body_type_id BIGINT,
    ADD COLUMN capacity_id  BIGINT;

UPDATE vehicle_intake
   SET body_type_id = COALESCE(edited_body_type_id, matched_body_type_id),
       capacity_id  = (SELECT c.id
                         FROM capacities c
                        WHERE lower(c.label) = lower(btrim(
                                  COALESCE(NULLIF(btrim(vehicle_intake.edited_capacity), ''),
                                           vehicle_intake.reported_capacity))));

-- RESTRICT, matching vehicles.body_type_id: retiring one must not silently blank work in progress.
ALTER TABLE vehicle_intake
    ADD CONSTRAINT fk_intake_body_type
        FOREIGN KEY (body_type_id) REFERENCES body_types (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_intake_capacity
        FOREIGN KEY (capacity_id) REFERENCES capacities (id) ON DELETE RESTRICT;

-- Dropping matched_body_type_id takes fk_intake_matched_body_type with it.
ALTER TABLE vehicle_intake
    DROP COLUMN edited_body_type_id,
    DROP COLUMN edited_capacity,
    DROP COLUMN reported_body_type,
    DROP COLUMN reported_capacity,
    DROP COLUMN matched_body_type_id;

COMMENT ON COLUMN vehicle_intake.body_type_id IS
    'Body type: the id the mobile app reported, then whatever the CSR picks. body_types.id.';
COMMENT ON COLUMN vehicle_intake.capacity_id IS
    'Capacity: the id the mobile app reported, then the CSR''s pick. capacities.id; completion '
    'writes its label to vehicles.capacity, which stays free text.';

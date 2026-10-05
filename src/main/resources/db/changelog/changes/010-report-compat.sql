--liquibase formatted sql
--
-- The six fields the mobile app already sends that had nowhere to land.
--
-- The app was built against FreightDesk's POST /api/trucks/report and is NOT being changed --
-- it gets a new base URL and nothing else. That contract carries six details this table had no
-- column for, so without these the API would have to accept them and throw them away. Silently
-- discarding what a field executive typed is worse than not asking for it: the CSR calling the
-- driver would see an empty screen for facts the reporter had already supplied.
--
-- The `reported_` ones are free text, matching the columns already here. That prefix is the
-- whole point -- these are a human's claim from the roadside, and they sit beside `ocr_` (what
-- the machine read) and `edited_` (what the CSR concluded) without any of the three being
-- mistaken for another.
--
-- Body type and capacity are NOT here: they are single columns, `body_type_id` and `capacity`,
-- added in 008. The app's body type is resolved against the master at upload, and an
-- unrecognised name leaves body_type_id null rather than costing us the photo.

--changeset vehiclemanagement:20-report-compat
--comment Fields the mobile app's existing report contract sends that had no column
--rollback ALTER TABLE vehicle_intake DROP COLUMN reported_driver_name,
--rollback                            DROP COLUMN reported_loaded_status,
--rollback                            DROP COLUMN reported_material_type,
--rollback                            DROP COLUMN reported_no_of_wheels,
--rollback                            DROP COLUMN reported_axle_type;

ALTER TABLE vehicle_intake
    -- The driver's name as told to the reporter, so the broker can open the call with it.
    ADD COLUMN reported_driver_name   VARCHAR(128),
    -- 'loaded' | 'unloaded' as the app spells it. Unconstrained on purpose: a CHECK here would
    -- reject a future third option from an app release we do not control.
    ADD COLUMN reported_loaded_status VARCHAR(16),
    -- What it carries, e.g. 'Steel', 'Cement'. Helps the CSR judge cargo fit before ringing.
    ADD COLUMN reported_material_type VARCHAR(64),
    ADD COLUMN reported_no_of_wheels  SMALLINT,
    ADD COLUMN reported_axle_type     VARCHAR(32);

-- A negative or absurd wheel count is a client bug, not data. Bounded rather than exact,
-- because the app sends free integers and 22-wheelers exist.
ALTER TABLE vehicle_intake
    ADD CONSTRAINT ck_intake_reported_wheels
        CHECK (reported_no_of_wheels IS NULL
               OR reported_no_of_wheels BETWEEN 2 AND 32);

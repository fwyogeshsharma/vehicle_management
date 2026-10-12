-- The import's persistent tables. Run once, committed, before the first dry run, so body types
-- can be mapped while every run of import.sql is still rolling back. import.sql includes this
-- file too, so running it again is harmless.
--
--   psql "<vm-url>" -f setup.sql

CREATE SCHEMA IF NOT EXISTS fd_import;

-- FreightDesk's app sends its own body type spellings ("Container", "Open"); vehicle_management
-- has a master. A value that matches a body_types name exactly (ignoring case) maps by itself;
-- anything else needs a row here, keyed by lower(trim(value)). import.sql lists what is missing.
CREATE TABLE IF NOT EXISTS fd_import.body_type_map (
    fd_body_type text   PRIMARY KEY,
    body_type_id bigint NOT NULL REFERENCES body_types (id));

-- Every FreightDesk row already imported, and what it became. A re-run skips these.
CREATE TABLE IF NOT EXISTS fd_import.loaded (
    truck_id   bigint      PRIMARY KEY,
    target     text        NOT NULL CHECK (target IN ('vehicle', 'intake')),
    vehicle_id bigint,
    intake_id  bigint,
    loaded_at  timestamptz NOT NULL DEFAULT now());

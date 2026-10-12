-- One-time import of FreightDesk `trucks` into vehicle_management.
--
-- Run against the vehicle_management database, from the directory holding the CSVs that
-- export.sql wrote:
--
--   psql "<vm-url>" -f import.sql                 dry run: does everything, reports, ROLLS BACK
--   psql "<vm-url>" -v commit=1 -f import.sql     the same, and COMMITS
--
-- What goes where, by FreightDesk's Trust badge (webapp/broker_grouping.py::lead_trust):
--
--   Verified        (image_api, review_status PASSED), no photos  -> vehicles
--   Pending Review  (image_api, review_status NULL/PENDING), photos -> vehicle_intake
--   everything else -> not imported; counted in the report with the reason
--
-- Safe to re-run. fd_import.loaded records every FreightDesk row already imported, and a
-- re-run skips them -- so fixing a body type mapping and running again picks up only the rows
-- that failed for that reason.
--
-- The vehicle load follows VehicleService.intake, the path a CSR's "complete" takes: the
-- truck's number is the driver, found or created by mobile; a company name makes the truck
-- company-owned, with the driver put on the company's books first; with no company the driver
-- owns it. It is written in SQL rather than through the service because the schema's rules are
-- CHECKs and foreign keys, which hold here exactly as they do for Hibernate.
--
-- PHOTOS: copy FreightDesk's report photos into the vehicle_management bucket, keys unchanged,
-- BEFORE committing. Intake rows carry the keys, and a QUEUED one is claimed by the OCR worker
-- as soon as this commits.

\set ON_ERROR_STOP on

BEGIN;

CREATE SCHEMA IF NOT EXISTS fd_import;   -- setup.sql, below, makes the rest

-- -- Staging: reloaded from the CSVs on every run ----------------------------------------------

DROP TABLE IF EXISTS fd_import.trucks, fd_import.submission_log CASCADE;

CREATE TABLE fd_import.trucks (
    id bigint PRIMARY KEY, source text, detected_at timestamptz, license_plate text,
    plate_confidence text, company_name text, phone_number text, city text, other_text text,
    loaded_status text, body_type text, material_type text, driver_name text, location text,
    latitude float8, longitude float8, num_wheels int, axle_type text, phone_reported text,
    phone_ocr text, reported_by text, reporter_phone text, verification_status text,
    review_status text, reviewed_by text, reviewed_at timestamptz, review_note text,
    processing_status text, processing_error text, processed_at timestamptz, image_keys jsonb,
    plate_candidates jsonb, body_texts jsonb, created_at timestamptz);

CREATE TABLE fd_import.submission_log (
    id bigint PRIMARY KEY, truck_id bigint, vehicle_reported text, vehicle_ocr text);

\copy fd_import.trucks FROM 'fd_trucks.csv' CSV HEADER
\copy fd_import.submission_log FROM 'fd_submission_log.csv' CSV HEADER

-- -- Kept between runs: fd_import.body_type_map and fd_import.loaded ---------------------------

\ir setup.sql

-- -- Helpers -------------------------------------------------------------------------------------

-- Trim and collapse whitespace; blank becomes NULL. Normalizer.clean.
CREATE OR REPLACE FUNCTION fd_import.clean(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT nullif(regexp_replace(btrim(t), '\s+', ' ', 'g'), '')
$$;

-- Every valid Indian mobile in a free-text phone field, 10 digits each, in the order written.
-- Normalizer.mobile's rule (+91 / 91 / 0 prefixes, must start 6-9), applied per number: a
-- FreightDesk phone column often holds several, separated by commas or slashes.
CREATE OR REPLACE FUNCTION fd_import.mobiles(t text) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce(jsonb_agg(n ORDER BY first_seen), '[]'::jsonb)
      FROM (SELECT right(d, 10) AS n, min(ord) AS first_seen
              FROM (SELECT regexp_replace(p, '\D', '', 'g') AS d, ord
                      FROM regexp_split_to_table(coalesce(t, ''), '[,;/|]')
                           WITH ORDINALITY AS s(p, ord)) digits
             WHERE d ~ '^(91|0)?[6-9][0-9]{9}$'
             GROUP BY right(d, 10)) nums
$$;

-- -- Which bucket each FreightDesk row falls in -------------------------------------------------

CREATE OR REPLACE VIEW fd_import.classified AS
SELECT t.*,
       CASE
           WHEN t.source <> 'image_api'                      THEN 'skip: Auto Verified (video/stream)'
           WHEN t.review_status = 'REJECTED'                 THEN 'skip: Rejected'
           WHEN t.review_status = 'PASSED' AND NOT has_photos THEN 'vehicle'
           WHEN t.review_status = 'PASSED'                   THEN 'skip: Verified, has photos'
           WHEN has_photos                                   THEN 'intake'
           ELSE                                                   'skip: Pending Review, no photos'
       END AS bucket
  FROM (SELECT t.*,
               CASE WHEN jsonb_typeof(t.image_keys) = 'array'
                    THEN jsonb_array_length(t.image_keys) > 0
                    ELSE false END AS has_photos
          FROM fd_import.trucks t) t;

-- =================================================================================================
-- 1. Verified, no photos  ->  vehicles
-- =================================================================================================

-- Every candidate row, with its fields put into vehicle_management's shape.
CREATE TEMP TABLE vrows ON COMMIT DROP AS
SELECT x.*,
       (fd_import.mobiles(concat_ws(',', x.phone_number, x.phone_reported)) - coalesce(x.mobile, ''))
           ->> 0 AS alt_mobile
  FROM (SELECT c.id AS truck_id, c.detected_at, c.phone_number, c.phone_reported,
               upper(regexp_replace(coalesce(c.license_plate, ''), '[\s-]+', '', 'g')) AS plate,
               coalesce(fd_import.mobiles(c.phone_number)   ->> 0,
                        fd_import.mobiles(c.phone_reported) ->> 0,
                        fd_import.mobiles(c.phone_ocr)      ->> 0) AS mobile,
               left(fd_import.clean(c.driver_name), 120)  AS driver_name,
               left(fd_import.clean(c.company_name), 160) AS company,
               lower(fd_import.clean(c.body_type))        AS fd_body_type,
               coalesce(m.body_type_id, bt.id)            AS body_type_id,
               CASE WHEN c.num_wheels BETWEEN 2 AND 32 AND mod(c.num_wheels, 2) = 0
                    THEN c.num_wheels END::smallint       AS wheels,
               CASE WHEN substring(c.axle_type from '([0-9]+)')::int BETWEEN 1 AND 12
                    THEN substring(c.axle_type from '([0-9]+)')::int END::smallint AS axles,
               fd_import.clean(c.city)                    AS city
          FROM fd_import.classified c
          LEFT JOIN fd_import.body_type_map m ON m.fd_body_type = lower(fd_import.clean(c.body_type))
          LEFT JOIN body_types bt ON bt.name_key = lower(fd_import.clean(c.body_type)) AND bt.is_active
         WHERE c.bucket = 'vehicle'
           AND NOT EXISTS (SELECT 1 FROM fd_import.loaded l WHERE l.truck_id = c.id)) x;

-- Why a row cannot become a vehicle on its own, or NULL when it can. The registration pattern
-- is Normalizer.REGISTRATION, stricter than the CHECK, so an imported plate is one the API
-- would also accept on the next edit. It rejects the BH series, as the API does.
ALTER TABLE vrows ADD COLUMN reason text;
UPDATE vrows r SET reason = CASE
    WHEN r.plate !~ '^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{1,4}$' THEN 'plate is not a valid registration'
    WHEN r.mobile IS NULL                                     THEN 'no valid 10-digit mobile'
    WHEN r.body_type_id IS NULL                               THEN 'body type not mapped'
    WHEN EXISTS (SELECT 1 FROM vehicles v WHERE v.registration_number = r.plate)
                                                              THEN 'plate already registered in vehicle_management'
    WHEN EXISTS (SELECT 1 FROM users u WHERE u.mobile = r.mobile AND NOT u.is_active)
                                                              THEN 'mobile belongs to a deactivated user'
END;

-- One vehicle per plate. FreightDesk holds many rows per truck -- repeat sightings, and the
-- payment import writes one row per stop on a trip -- so the newest complete row wins, and
-- every other row with that plate is recorded as merged into the same vehicle.
CREATE TEMP TABLE vpick ON COMMIT DROP AS
SELECT DISTINCT ON (plate) *
  FROM vrows
 WHERE reason IS NULL
 ORDER BY plate, detected_at DESC, truck_id DESC;

-- Drivers: found by mobile, created when new. user_type DRIVER, as VehicleService.intake does,
-- whether or not they end up owning the truck. No login: username, password and role stay NULL.
INSERT INTO users (name, mobile, alt_mobile, user_type, notes)
SELECT DISTINCT ON (p.mobile)
       coalesce(p.driver_name, 'Driver ' || p.mobile),
       p.mobile, p.alt_mobile, 'DRIVER',
       'Imported from FreightDesk'
           || CASE WHEN p.driver_name IS NULL THEN '; name not known, placeholder given' ELSE '' END
  FROM vpick p
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.mobile = p.mobile)
 ORDER BY p.mobile, (p.driver_name IS NULL), p.detected_at DESC;

-- Companies: found by name_key (lower-cased clean name, CompanyService's rule), created when new.
INSERT INTO companies (name, name_key)
SELECT DISTINCT ON (lower(p.company)) p.company, lower(p.company)
  FROM vpick p
 WHERE p.company IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM companies c WHERE c.name_key = lower(p.company))
 ORDER BY lower(p.company), p.detected_at DESC;

-- The vehicles. Exactly one owner: the company when there is one, otherwise the driver.
INSERT INTO vehicles (registration_number, body_type_id, no_of_axles, no_of_wheels,
                      owner_company_id, owner_user_id, notes)
SELECT p.plate, p.body_type_id, p.axles, p.wheels,
       co.id,
       CASE WHEN co.id IS NULL THEN u.id END,
       'Imported from FreightDesk trucks #' || p.truck_id
  FROM vpick p
  JOIN users u          ON u.mobile = p.mobile
  LEFT JOIN companies co ON co.name_key = lower(p.company);

-- Employment BEFORE the assignment: fk_vxu_employed refuses a company driver who is not on the
-- company's books, so this order is the database's, not a preference.
INSERT INTO user_x_company (user_id, company_id, position)
SELECT DISTINCT u.id, v.owner_company_id, 'Driver'
  FROM vpick p
  JOIN vehicles v ON v.registration_number = p.plate
  JOIN users u    ON u.mobile = p.mobile
 WHERE v.owner_company_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- The driver, as primary. owner_company_id / is_company_owned mirror the vehicle; the composite
-- FKs reject any row where they do not.
INSERT INTO vehicle_x_user (vehicle_id, user_id, is_primary, owner_company_id, is_company_owned)
SELECT v.id, u.id, true, v.owner_company_id, v.owner_company_id IS NOT NULL
  FROM vpick p
  JOIN vehicles v ON v.registration_number = p.plate
  JOIN users u    ON u.mobile = p.mobile;

-- Where it runs: every FreightDesk city seen for that plate, as the vehicle's OWN location
-- (additive to its company's since changeset 009, so no company route is overwritten). Only a
-- city name that is unique across states is used -- "Sagar" is in two, and guessing puts the
-- truck in the wrong one.
INSERT INTO vehicle_x_location (vehicle_id, state_id, city_id)
SELECT DISTINCT v.id, ci.state_id, ci.id
  FROM vrows r
  JOIN vpick p    ON p.plate = r.plate
  JOIN vehicles v ON v.registration_number = p.plate
  JOIN (SELECT name_key, min(id) AS id, min(state_id) AS state_id
          FROM cities WHERE is_active
         GROUP BY name_key HAVING count(*) = 1) ci ON ci.name_key = lower(r.city)
 WHERE r.city IS NOT NULL
ON CONFLICT DO NOTHING;

-- Every row of a loaded plate is now accounted for, including the ones merged into it.
INSERT INTO fd_import.loaded (truck_id, target, vehicle_id)
SELECT r.truck_id, 'vehicle', v.id
  FROM vrows r
  JOIN vpick p    ON p.plate = r.plate
  JOIN vehicles v ON v.registration_number = p.plate;

-- =================================================================================================
-- 2. Pending Review, with photos  ->  vehicle_intake
-- =================================================================================================

-- Reserve an intake id per row first, so the insert below can use it and the record of what
-- was loaded is written in the same statement order.
INSERT INTO fd_import.loaded (truck_id, target, intake_id)
SELECT c.id, 'intake', nextval('vehicle_intake_id_seq')
  FROM fd_import.classified c
 WHERE c.bucket = 'intake'
   AND NOT EXISTS (SELECT 1 FROM fd_import.loaded l WHERE l.truck_id = c.id)
 ORDER BY c.id;

INSERT INTO vehicle_intake (
    id, reported_plate, reported_mobile, reported_company, reported_by, reporter_mobile,
    captured_at, location, latitude, longitude, image_keys,
    processing_status, processing_error, processed_at,
    ocr_plate, ocr_mobiles, ocr_confidence, ocr_raw,
    review_status, review_note,
    reported_driver_name, reported_loaded_status, reported_body_type, reported_material_type,
    reported_no_of_wheels, reported_axle_type, created_at)
SELECT l.intake_id,
       left(coalesce(s.vehicle_reported, t.license_plate), 32),
       coalesce(fd_import.mobiles(t.phone_reported) ->> 0,
                nullif(left(regexp_replace(coalesce(t.phone_reported, ''), '\D', '', 'g'), 16), '')),
       left(t.company_name, 160),
       t.reported_by,
       fd_import.mobiles(t.reporter_phone) ->> 0,
       t.detected_at, coalesce(t.location, t.city), t.latitude, t.longitude,
       t.image_keys,
       -- DONE and FAILED carry over; anything unfinished is queued for this system's worker
       CASE t.processing_status WHEN 'DONE' THEN 'DONE' WHEN 'FAILED' THEN 'FAILED' ELSE 'QUEUED' END,
       CASE WHEN t.processing_status IN ('DONE', 'FAILED') THEN t.processing_error END,
       CASE WHEN t.processing_status IN ('DONE', 'FAILED') THEN coalesce(t.processed_at, t.created_at) END,
       left(coalesce(s.vehicle_ocr,
                     CASE WHEN t.plate_confidence IN ('HIGH', 'LOW') THEN t.license_plate END), 32),
       fd_import.mobiles(t.phone_ocr),
       t.plate_confidence,
       jsonb_strip_nulls(jsonb_build_object(
           'freightdesk_id', t.id, 'plate_candidates', t.plate_candidates,
           'body_texts', t.body_texts, 'other_text', t.other_text,
           'verification_status', t.verification_status)),
       'PENDING',
       left(t.review_note, 500),
       t.driver_name, lower(t.loaded_status), left(t.body_type, 64), t.material_type,
       CASE WHEN t.num_wheels BETWEEN 2 AND 32 THEN t.num_wheels END,
       t.axle_type,
       t.created_at
  FROM fd_import.loaded l
  JOIN fd_import.trucks t ON t.id = l.truck_id
  LEFT JOIN LATERAL (SELECT sl.vehicle_reported, sl.vehicle_ocr
                       FROM fd_import.submission_log sl
                      WHERE sl.truck_id = t.id
                      ORDER BY sl.id DESC LIMIT 1) s ON true
 WHERE l.target = 'intake'
   AND NOT EXISTS (SELECT 1 FROM vehicle_intake vi WHERE vi.id = l.intake_id);

-- =================================================================================================
-- Report
-- =================================================================================================

\echo
\echo '== FreightDesk rows by bucket'
SELECT bucket, count(*) AS rows FROM fd_import.classified GROUP BY 1 ORDER BY 1;

\echo '== Imported so far (all runs)'
SELECT target, count(*) AS rows,
       count(DISTINCT vehicle_id) FILTER (WHERE target = 'vehicle') AS vehicles
  FROM fd_import.loaded GROUP BY 1 ORDER BY 1;

\echo '== Verified rows NOT imported, by reason (details in fd_vehicle_exceptions.csv)'
SELECT r.reason, count(*) AS rows, count(DISTINCT r.plate) AS plates
  FROM vrows r
 WHERE NOT EXISTS (SELECT 1 FROM fd_import.loaded l WHERE l.truck_id = r.truck_id)
 GROUP BY 1 ORDER BY 2 DESC;

\echo '== Unmapped FreightDesk body types (add to fd_import.body_type_map and re-run)'
SELECT fd_body_type, count(*) AS rows
  FROM vrows
 WHERE body_type_id IS NULL
 GROUP BY 1 ORDER BY 2 DESC;

\echo '== Drivers created with a placeholder name'
SELECT count(*) AS users FROM users
 WHERE notes LIKE 'Imported from FreightDesk; name not known%';

\copy (SELECT r.truck_id, r.plate, r.mobile, r.company, r.fd_body_type, r.reason FROM vrows r WHERE NOT EXISTS (SELECT 1 FROM fd_import.loaded l WHERE l.truck_id = r.truck_id) ORDER BY r.reason, r.plate) TO 'fd_vehicle_exceptions.csv' CSV HEADER

\if :{?commit}
    COMMIT;
    \echo 'COMMITTED.'
\else
    ROLLBACK;
    \echo 'DRY RUN - rolled back. Re-run with -v commit=1 to keep it.'
\endif

-- Run against the FreightDesk database. Writes two CSVs into the current directory.
--
--   psql "<freightdesk-url>" -f export.sql
--
-- Every row is exported, whatever its source or trust, so import.sql can report what it
-- skipped and why rather than silently never seeing it.

\set ON_ERROR_STOP on

\copy (SELECT id, source::text, detected_at, license_plate, plate_confidence, company_name, phone_number, city, other_text, loaded_status, body_type, material_type, driver_name, location, latitude, longitude, num_wheels, axle_type, phone_reported, phone_ocr, reported_by, reporter_phone, verification_status, review_status, reviewed_by, reviewed_at, review_note, processing_status, processing_error, processed_at, image_keys, plate_candidates, body_texts, created_at FROM trucks ORDER BY id) TO 'fd_trucks.csv' CSV HEADER

\copy (SELECT id, truck_id, vehicle_reported, vehicle_ocr FROM submission_log WHERE truck_id IS NOT NULL ORDER BY id) TO 'fd_submission_log.csv' CSV HEADER

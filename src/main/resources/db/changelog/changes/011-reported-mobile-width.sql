--liquibase formatted sql
--
-- Widen reported_mobile so a mistyped number costs us the number, not the photo.
--
-- The column was VARCHAR(10) because a canonical Indian mobile is ten digits, and the upload
-- path enforced that with Normalizer.mobile -- which THROWS. So a field executive who misread
-- one digit off the side of a truck got a 400 and the photos were never stored at all.
--
-- The mobile app the intake endpoint now serves has always been allowed to send an unusable
-- phone_number (its contract says so explicitly: the report is still stored, the telecaller
-- just has nothing to dial). Rejecting the whole submission over it is both a regression
-- against that app's current behaviour and the wrong trade: the photos are the asset, the
-- typed number is a hint, and OCR is about to read the number off the truck anyway.
--
-- So: keep whatever digits the reporter typed, and let the CSR judge them against the photo.
-- 16 is room for a +91-prefixed twelve plus slack, while still being short enough that a
-- pasted paragraph is rejected rather than stored (IntakeService drops anything longer).
--
-- Canonicalisation did not go away -- valid numbers are still normalised to ten digits on the
-- way in, so lookups and duplicate checks are unaffected. Only the failure case changed, from
-- "reject the request" to "store it as typed".

--changeset vehiclemanagement:21-reported-mobile-width
--comment Keep an unparseable reported number instead of rejecting the upload
--rollback ALTER TABLE vehicle_intake ALTER COLUMN reported_mobile TYPE VARCHAR(10);

ALTER TABLE vehicle_intake
    ALTER COLUMN reported_mobile TYPE VARCHAR(16);

COMMENT ON COLUMN vehicle_intake.reported_mobile IS
    'What the reporter typed, digits only. Normalised to 10 digits when it parses as an Indian '
    'mobile; kept verbatim when it does not, because losing the photo over a misread digit is '
    'the worse outcome. Never assume this is dialable -- check the length.';

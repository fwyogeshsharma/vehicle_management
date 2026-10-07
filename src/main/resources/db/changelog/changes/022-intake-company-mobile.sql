--liquibase formatted sql
--
-- The company's own number, kept apart from the truck's numbers.
--
-- `reported_mobile` / `ocr_mobiles` / `edited_mobiles` are numbers painted on or reported for the
-- truck, and the CSR form had three boxes (driver, alt, company) fed from that one list, so after
-- a save the box positions were lost. The transport office's number is a different fact -- it is
-- what ends up on companies.mobile -- so it gets its own column pair, the same reported/edited
-- split the other fields follow. There is no ocr_ counterpart: OCR returns an unlabelled list.
--
-- Plain VARCHAR(16), no CHECK, like reported_mobile (021): the field app may send an unusable
-- number and the report is stored anyway; the CSR judges it. Hard validation is at completion.

--changeset vehiclemanagement:22-intake-company-mobile
--comment Company's own number on vehicle_intake: reported by the app, corrected by the CSR
--rollback ALTER TABLE vehicle_intake DROP COLUMN edited_company_mobile, DROP COLUMN reported_company_mobile;

ALTER TABLE vehicle_intake
    ADD COLUMN reported_company_mobile VARCHAR(16),
    ADD COLUMN edited_company_mobile   VARCHAR(16);

COMMENT ON COLUMN vehicle_intake.reported_company_mobile IS
    'The company''s number as the field app sent it. A claim, not a fact.';
COMMENT ON COLUMN vehicle_intake.edited_company_mobile IS
    'The CSR''s answer for the company''s number. Wins over reported; NULL = uncorrected, '''' = cleared.';

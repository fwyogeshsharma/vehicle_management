--liquibase formatted sql
--
-- Make "registration contains…" stop scanning the fleet.
--
-- `VehicleRepository.search` matches the plate with `ILIKE '%...%'`, and a leading wildcard
-- cannot use the btree behind `uq_vehicles_reg`. Measured on 200,000 vehicles:
--
--     registration ILIKE '%1234%'      page 202 ms   count 176 ms
--     the same, with the index below   page   1 ms   count   1 ms
--
-- A trigram GIN index is the right shape for this because the query genuinely is "contains".
-- Anchoring the search to a prefix instead would let the existing btree serve it, and was
-- rejected: a dispatcher holding the last four digits off a photograph is the whole reason
-- this filter exists, and they do not know the state code.
--
-- **This needs the pg_trgm extension**, which is contrib rather than core. On this machine and
-- on any managed Postgres worth using it is available to install; if a deployment's role
-- cannot create it, THIS CHANGESET FAILS THE DEPLOY rather than skipping quietly, which is
-- correct — a silently absent index is a table scan nobody notices until the table is big.
--
-- Deliberately only `registration_number`. Company, customer and person names are also
-- searched with a leading wildcard and would benefit from the same treatment, but none of
-- them has been measured yet and an index nobody has proved they need is still a write cost
-- on every insert.

--changeset vehiclemanagement:30-pg-trgm
--comment The trigram extension, for contains-searches that cannot use a btree
--rollback DROP EXTENSION IF EXISTS pg_trgm;

CREATE EXTENSION IF NOT EXISTS pg_trgm;


--changeset vehiclemanagement:31-vehicles-registration-trgm
--comment Trigram index so "registration contains" is a lookup, not a scan
--rollback DROP INDEX idx_vehicles_reg_trgm;

-- GIN rather than GiST: this column is read far more than it is written, and GIN answers a
-- containment match faster at the cost of a slower update, which is the right way round for a
-- registration number that is set once and effectively never edited.
CREATE INDEX idx_vehicles_reg_trgm
    ON vehicles USING gin (registration_number gin_trgm_ops);

COMMENT ON INDEX idx_vehicles_reg_trgm IS
    'Serves ILIKE ''%...%'' on the plate. The btree from uq_vehicles_reg cannot: a leading '
    'wildcard makes it unusable, which is a full scan once the fleet is real.';

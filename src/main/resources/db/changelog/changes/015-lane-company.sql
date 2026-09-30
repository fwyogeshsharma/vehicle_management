--liquibase formatted sql
--
-- A lane is a company and a season, not a volume and a rate.
--
-- The 014 changeset asked a CSR for trips per month and an indicative rate. Neither survived
-- contact with the work: both are numbers nobody volunteers on a call about something else,
-- and a field that is usually blank is a field that gets filled in with a guess the first time
-- somebody feels it ought to have a value. What a CSR does reliably know is WHO is moving it
-- and WHETHER it only moves part of the year.
--
-- **This drops two columns and the data in them.** There is no way to preserve it: the rollback
-- restores the columns empty. On this deployment they hold four rows of development data, and
-- the change is worth more than the rows.
--
-- The aggregate report built on those two columns goes with them -- see LaneController, which
-- no longer has a /top route. Ranking lanes by how many times they had been reported was the
-- other half of the same idea, and it is out for the same reason: it measured how talkative
-- the CSRs had been, not how real the lane was.

--changeset vehiclemanagement:28-lane-company
--comment Replace volume/rate with the company and a seasonal flag
--rollback ALTER TABLE freight_lanes DROP COLUMN company_name,
--rollback                           DROP COLUMN is_seasonal;
--rollback ALTER TABLE freight_lanes ADD COLUMN trips_per_month SMALLINT,
--rollback                           ADD COLUMN indicative_rate NUMERIC(12,2);
--rollback ALTER TABLE freight_lanes ADD CONSTRAINT ck_lane_trips
--rollback     CHECK (trips_per_month IS NULL OR trips_per_month BETWEEN 1 AND 1000);
--rollback ALTER TABLE freight_lanes ADD CONSTRAINT ck_lane_rate
--rollback     CHECK (indicative_rate IS NULL OR indicative_rate >= 0);

ALTER TABLE freight_lanes
    DROP COLUMN trips_per_month,
    DROP COLUMN indicative_rate;

ALTER TABLE freight_lanes
    -- Whose cargo it is. **Free text, and deliberately not a foreign key to consignors.** A
    -- lane is hearsay: "I hear Sharma Traders send mangoes that way" should not create a
    -- customer record, because a customer record is something a receipt can be issued against
    -- and this is a rumour. The UI offers existing customers as suggestions; picking one
    -- copies the name and nothing more.
    ADD COLUMN company_name VARCHAR(160),
    -- Whether it runs all year. A flag rather than only the free-text `season`, because
    -- "which lanes are seasonal" is a question worth filtering on and "Apr-Jul" is not
    -- something a WHERE clause can read. The text stays as the detail beneath the flag.
    ADD COLUMN is_seasonal BOOLEAN NOT NULL DEFAULT FALSE;

-- "Which lanes are running right now" is the filter this exists to serve.
CREATE INDEX idx_lanes_seasonal ON freight_lanes (is_seasonal) WHERE is_active;

COMMENT ON COLUMN freight_lanes.company_name IS
    'Whose cargo, as told to us. NOT a link to consignors -- recording a rumour must not '
    'create a customer that a receipt could then be issued against.';
COMMENT ON COLUMN freight_lanes.is_seasonal IS
    'True when the lane only runs part of the year; `season` says which part.';

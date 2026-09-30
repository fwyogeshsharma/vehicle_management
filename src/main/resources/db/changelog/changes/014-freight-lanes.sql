--liquibase formatted sql
--
-- What moves where: the lanes a CSR hears about but nobody has a receipt for yet.
--
-- "Meerut to Lucknow, mangoes go." A driver mentions it, a consignor mentions it, and today
-- that sentence dies with the call. This table keeps it, because the question it answers --
-- which routes and commodities should we be quoting for that we currently are not -- cannot
-- be answered from lorry_receipts. Receipts record business already won; this records business
-- that exists and is going to somebody else.
--
-- **Deliberately NOT a lorry receipt, and not a constraint on one.** A lane is hearsay: it has
-- no consignor, no truck, no money and no obligation attached. Storing it in the same table as
-- the register -- even behind a status -- would mean every query about real consignments had
-- to start by excluding rumours, which is exactly how vehicle_intake was kept out of vehicles.
--
-- **Duplicates are the point.** Three CSRs hearing the same lane from three drivers is the
-- strongest signal in here, so there is no unique key across (from, to, goods). The
-- corroboration is the data; deduplicating it would throw away the only evidence of volume.

--changeset vehiclemanagement:27-freight-lanes
--comment Lane intelligence: what commodity moves on which route
--rollback DROP TABLE freight_lanes;

CREATE TABLE freight_lanes (
    id BIGSERIAL,

    -- The same link-plus-snapshot discipline lorry_receipts uses, for the same reason: the
    -- city link makes "everything out of Meerut" work, and the text survives a city being
    -- renamed or retired, or a place that is not a city at all ("Kashipur industrial area").
    from_city_id BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    from_place   VARCHAR(120) NOT NULL,
    to_city_id   BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    to_place     VARCHAR(120) NOT NULL,

    goods_type_id BIGINT REFERENCES goods_types (id) ON DELETE SET NULL,
    goods         VARCHAR(160) NOT NULL,

    -- Seasonality is the whole story for a lot of this. Mangoes move Apr-Jul and not
    -- otherwise, and a lane recorded without that reads all year as an opportunity we are
    -- missing. Free text, because "monsoon" and "after Diwali" are how people actually say it.
    season VARCHAR(80),

    -- Roughly how much, as told to us. Nullable and approximate on purpose -- a CSR who has to
    -- produce a number they do not have will invent one, and an invented number outranks an
    -- honest blank in every report that follows.
    trips_per_month SMALLINT,
    -- What kind of truck it needs. RESTRICT would be wrong here: this is a note, not a fleet
    -- record, and retiring a body type must not block editing an unrelated lane.
    body_type_id    BIGINT REFERENCES body_types (id) ON DELETE SET NULL,
    -- Indicative freight, if they said. Not a quote and not binding.
    indicative_rate NUMERIC(12,2),

    -- Who said so. A lane worth acting on is a lane somebody can be rung back about.
    source        VARCHAR(160),
    source_mobile VARCHAR(10),

    notes VARCHAR(2000),

    -- Retired rather than deleted once a lane is stale or turns out to be wrong: the fact that
    -- it was reported and did not work out is itself worth keeping the next time somebody
    -- suggests it.
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    recorded_by VARCHAR(128),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_freight_lanes PRIMARY KEY (id),
    CONSTRAINT ck_lane_places CHECK (btrim(from_place) <> '' AND btrim(to_place) <> ''),
    CONSTRAINT ck_lane_goods  CHECK (btrim(goods) <> ''),
    CONSTRAINT ck_lane_trips  CHECK (trips_per_month IS NULL
                                     OR trips_per_month BETWEEN 1 AND 1000),
    CONSTRAINT ck_lane_rate   CHECK (indicative_rate IS NULL OR indicative_rate >= 0),
    CONSTRAINT ck_lane_mobile CHECK (source_mobile IS NULL
                                     OR source_mobile ~ '^[6-9][0-9]{9}$')
);

CREATE TRIGGER trg_freight_lanes_updated BEFORE UPDATE ON freight_lanes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The register is read newest first; the report groups by route and commodity.
CREATE INDEX idx_lanes_recent ON freight_lanes (created_at DESC);
CREATE INDEX idx_lanes_route  ON freight_lanes (from_city_id, to_city_id)
    WHERE is_active;
-- Lower-cased, because the grouping that answers "which lane comes up most" must not treat
-- "Mangoes" and "mangoes" as two commodities.
CREATE INDEX idx_lanes_grouping ON freight_lanes
    (lower(from_place), lower(to_place), lower(goods)) WHERE is_active;

COMMENT ON TABLE freight_lanes IS
    'Lane intelligence: what commodity moves on which route, as reported to a CSR. Hearsay, '
    'not consignments -- no obligation attaches. Duplicates are deliberate: repeated reports '
    'of the same lane are the evidence that it is real.';

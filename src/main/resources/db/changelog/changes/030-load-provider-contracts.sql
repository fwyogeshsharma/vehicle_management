--liquibase formatted sql
--
-- What a load provider has agreed to pay us, and how that price breaks down.
--
-- A "load provider" is the company that gives us the cargo to move. For each of them, at each
-- location, the desk agrees a price and a set of deductions, and the truck owner is finally paid
-- what is left. This table keeps those terms in one place so they stop living in a notebook.
--
-- **Every figure is stored as typed, none is computed.** price_approved, the charges,
-- commission, munshiana, dala, final_price_for_truck and per_tonne_price are all entered by
-- the person who agreed them. Recomputing final_price_for_truck here would bake in a formula
-- that differs from one provider to the next, and the day it disagrees with the number on the
-- signed paper, the table is the one that is wrong.
--
-- **No unique key.** The same provider at the same place is re-negotiated over time, and the
-- history is worth more than a tidy table. The newest row is simply the newest.
--
-- **Company is text, not a link to consignors.** Same rule as freight_lanes: recording terms
-- must not create a customer a lorry receipt could then be issued against.

--changeset vehiclemanagement:34-load-provider-contracts
--comment load_provider_contracts: agreed price and deductions per load provider and location
--rollback DROP TABLE load_provider_contracts;

CREATE TABLE load_provider_contracts (
    id BIGSERIAL,

    company_name VARCHAR(160) NOT NULL,

    -- Link-plus-snapshot, as on lorry receipts and lanes: the link makes "everything at
    -- Meerut" work, the text survives a place that is not a city ("Kashipur industrial area").
    city_id  BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    location VARCHAR(160),

    -- Money, in rupees.
    price_approved        NUMERIC(12,2),
    loading_charges       NUMERIC(12,2),
    unloading_charges     NUMERIC(12,2),
    commission_percentage NUMERIC(5,2),
    munshiana             NUMERIC(12,2),
    dala                  NUMERIC(12,2),
    final_price_for_truck NUMERIC(12,2),
    per_tonne_price       NUMERIC(12,2),

    notes VARCHAR(2000),

    -- Retired, not deleted: superseded terms are the record of what was agreed before.
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    recorded_by VARCHAR(128),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_load_provider_contracts PRIMARY KEY (id),
    CONSTRAINT ck_lpc_company    CHECK (btrim(company_name) <> ''),
    CONSTRAINT ck_lpc_location   CHECK (location IS NULL OR btrim(location) <> ''),
    CONSTRAINT ck_lpc_commission CHECK (commission_percentage IS NULL
                                        OR commission_percentage BETWEEN 0 AND 100),
    CONSTRAINT ck_lpc_amounts    CHECK (
        COALESCE(price_approved, 0)    >= 0 AND COALESCE(loading_charges, 0)       >= 0 AND
        COALESCE(unloading_charges, 0) >= 0 AND COALESCE(munshiana, 0)             >= 0 AND
        COALESCE(dala, 0)              >= 0 AND COALESCE(final_price_for_truck, 0) >= 0 AND
        COALESCE(per_tonne_price, 0)   >= 0)
);

CREATE TRIGGER trg_load_provider_contracts_updated BEFORE UPDATE ON load_provider_contracts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_lpc_recent  ON load_provider_contracts (created_at DESC);
CREATE INDEX idx_lpc_company ON load_provider_contracts (lower(company_name));

COMMENT ON TABLE load_provider_contracts IS
    'Agreed price and deductions per load provider company and location. Figures are stored as '
    'entered, never computed.';

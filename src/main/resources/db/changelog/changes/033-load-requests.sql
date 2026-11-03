--liquibase formatted sql
--
-- Daily load requests: a customer tells us they have cargo to move.
--
-- "Kumar Cement needs 3 trucks of 25 tons, Jaipur to Surat, tomorrow." Today that arrives by
-- phone and WhatsApp and is lost by evening. This table is the day's board: what was asked for,
-- by whom, and whether it has been covered.
--
-- **The customer is a row from the master (consignors), chosen, not typed.** company_name is a
-- snapshot of its name at the time, the link-plus-snapshot discipline lorry receipts use, so the
-- list reads without a join and an old request still says what it said if the customer is
-- renamed. RESTRICT: customers are retired, never deleted.
--
-- **A request is not a lorry receipt and creates no obligation.** It records an ask. The receipt
-- is what is issued once a truck is actually sent, and nothing here links to one yet: that
-- link is worth designing when somebody says how a request is meant to turn into a receipt.
--
-- **Places link to the city list when they can and stay text when they cannot**, as on receipts
-- and lanes: loads leave factory gates no city list contains.
--
-- offered_price is the TOTAL the customer offered for the run, as stated. Nothing is derived.
--
-- **No unique key.** The same customer asking for the same route two days running is normal,
-- and so is asking twice on one day for different trucks.

--changeset vehiclemanagement:37-load-requests
--comment load_requests: the day's cargo asks from customers
--rollback DROP TABLE load_requests;

CREATE TABLE load_requests (
    id BIGSERIAL,

    consignor_id BIGINT       NOT NULL REFERENCES consignors (id),
    company_name VARCHAR(160) NOT NULL,

    -- The day the ask arrived. Defaults to today so the board's "today" is what was entered today.
    request_date DATE NOT NULL DEFAULT CURRENT_DATE,

    from_city_id BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    from_place   VARCHAR(120) NOT NULL,
    to_city_id   BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    to_place     VARCHAR(120) NOT NULL,

    goods_type_id BIGINT REFERENCES goods_types (id) ON DELETE SET NULL,
    goods         VARCHAR(160),

    -- How much, and on how many trucks. Weight is for the whole request.
    weight_tons     NUMERIC(8,2),
    trucks_required SMALLINT NOT NULL DEFAULT 1,
    -- A note about the truck wanted, not a fleet record: retiring a body type must not block
    -- editing an old request.
    body_type_id    BIGINT REFERENCES body_types (id) ON DELETE SET NULL,

    -- When the cargo must be ready to load; not the day it was asked for.
    loading_date  DATE,
    offered_price NUMERIC(12,2),

    contact_name   VARCHAR(120),
    contact_mobile VARCHAR(10),
    notes          VARCHAR(2000),

    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',

    recorded_by VARCHAR(128),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_load_requests PRIMARY KEY (id),
    CONSTRAINT ck_lreq_company CHECK (btrim(company_name) <> ''),
    CONSTRAINT ck_lreq_places  CHECK (btrim(from_place) <> '' AND btrim(to_place) <> ''),
    CONSTRAINT ck_lreq_weight  CHECK (weight_tons IS NULL OR weight_tons > 0),
    CONSTRAINT ck_lreq_trucks  CHECK (trucks_required BETWEEN 1 AND 500),
    CONSTRAINT ck_lreq_price   CHECK (offered_price IS NULL OR offered_price >= 0),
    CONSTRAINT ck_lreq_mobile  CHECK (contact_mobile IS NULL OR contact_mobile ~ '^[6-9][0-9]{9}$'),
    CONSTRAINT ck_lreq_status  CHECK (status IN ('OPEN', 'FULFILLED', 'CANCELLED'))
);

CREATE TRIGGER trg_load_requests_updated BEFORE UPDATE ON load_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The board is read by day, newest first; the customer filter is the other common question.
CREATE INDEX idx_lreq_date     ON load_requests (request_date DESC, created_at DESC);
CREATE INDEX idx_lreq_consignor ON load_requests (consignor_id);
CREATE INDEX idx_lreq_open     ON load_requests (request_date) WHERE status = 'OPEN';

COMMENT ON TABLE load_requests IS
    'Daily cargo asks from customers (consignors). A request, not a consignment: no obligation '
    'attaches until a lorry receipt is issued.';

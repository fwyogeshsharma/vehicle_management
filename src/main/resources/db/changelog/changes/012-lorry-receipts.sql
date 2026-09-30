--liquibase formatted sql
--
-- Lorry receipts: the consignment note handed over when a truck is loaded.
--
-- Ported from tts (`V2__lorry_receipts.sql` + `V4__master_tables.sql`), with one large
-- simplification and four corrections.
--
-- **The simplification.** tts had to build `states` and `cities` in V4 and back-fill them from a
-- seed file, because it had nowhere to put a place. This system already has 36 states and 3,285
-- cities, a body-type master, a vehicle register and a people register — so an LR here links to
-- what already exists and only three tables are genuinely new.
--
-- **The idea worth copying exactly.** Every link below is nullable with ON DELETE SET NULL, AND
-- the details are also copied onto the row as text: `vehicle_id` beside `vehicle_number`,
-- `driver_user_id` beside `driver_name`. An LR is a record of what was agreed on a date. It must
-- stay readable when the truck is re-registered, the driver leaves, or the consignor is renamed
-- — and it must NOT silently change to match. The link is for navigation; the snapshot is the
-- document.

--changeset vehiclemanagement:22-consignors
--comment Customers who send and receive goods. Not companies -- see the comment.
--rollback DROP TABLE consignors;

-- Deliberately NOT `companies`. That table is documented as "a transport company: owns
-- vehicles, employs people", and it is wired into user_x_company employment rules and into
-- vehicle ownership. A consignor is a customer: it owns none of our trucks, employs none of our
-- drivers, and has no fleet. Folding the two together would mean every ownership rule in
-- VehicleService has to start by asking which kind of company it is holding.
--
-- One table for both roles, because the same firm is a consignor on Monday and a consignee on
-- Friday. tts made the same call and it is plainly right: two tables would duplicate a party
-- the first time anyone shipped in both directions.
CREATE TABLE consignors (
    id         BIGSERIAL,
    name       VARCHAR(160) NOT NULL,
    -- Lower-cased name, for find-or-create and duplicate detection. Same discipline as
    -- companies.name_key and body_types.name_key.
    name_key   VARCHAR(160) NOT NULL,
    mobile     VARCHAR(10),
    address    VARCHAR(300),
    gstin      VARCHAR(15),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_consignors PRIMARY KEY (id),
    CONSTRAINT uq_consignors_name_key UNIQUE (name_key),
    CONSTRAINT ck_consignors_name   CHECK (btrim(name) <> ''),
    CONSTRAINT ck_consignors_mobile CHECK (mobile IS NULL OR mobile ~ '^[6-9][0-9]{9}$'),
    -- Same pattern companies.gstin uses; a wrong GSTIN on an invoice is a real problem.
    CONSTRAINT ck_consignors_gstin  CHECK (gstin IS NULL
        OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$')
);

CREATE TRIGGER trg_consignors_updated BEFORE UPDATE ON consignors
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE consignors IS
    'Parties who send or receive goods. NOT companies -- that table is transport companies that '
    'own vehicles and employ drivers. One row serves both consignor and consignee roles.';


--changeset vehiclemanagement:23-goods-types
--comment Pick list of what gets carried
--rollback DROP TABLE goods_types;

-- A pick list, like capacities: `lorry_receipts.goods_description` stays free text and this
-- table only answers "what should the dropdown offer". The first LR for something nobody
-- has carried before must not be blocked on an administrator adding a master row.
CREATE TABLE goods_types (
    id         BIGSERIAL,
    name       VARCHAR(160) NOT NULL,
    name_key   VARCHAR(160) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_goods_types PRIMARY KEY (id),
    CONSTRAINT uq_goods_types_name_key UNIQUE (name_key),
    CONSTRAINT ck_goods_types_name CHECK (btrim(name) <> '')
);

CREATE TRIGGER trg_goods_types_updated BEFORE UPDATE ON goods_types
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO goods_types (name, name_key) VALUES
    ('Cement', 'cement'), ('Steel', 'steel'), ('Grains', 'grains'), ('Cotton', 'cotton'),
    ('Fertiliser', 'fertiliser'), ('Sugar', 'sugar'), ('Rice', 'rice'), ('Timber', 'timber'),
    ('Machinery', 'machinery'), ('Furniture', 'furniture'), ('Textiles', 'textiles'),
    ('Chemicals', 'chemicals'), ('Vegetables', 'vegetables'), ('Fruits', 'fruits'),
    ('Paper', 'paper'), ('Plastic granules', 'plastic granules'),
    ('Electronics', 'electronics'), ('General goods', 'general goods');


--changeset vehiclemanagement:24-lr-counters
--comment Gap-free LR numbering, one counter per series
--rollback DROP TABLE lr_counters;

-- **Why a counter table and not a sequence.**
--
-- tts numbers with `max(seq_no) + 1` read in one statement and written in the next, which two
-- clerks creating an LR in the same second both read — the second then fails on the unique
-- index. A PostgreSQL sequence fixes that race, but sequences are not transactional: a rolled
-- back insert burns its number and leaves a hole in the series.
--
-- For a consignment note a hole is the worse failure. The series is a document trail that gets
-- audited, and "LR-041 does not exist" is a question somebody has to answer. So: one row,
-- taken with SELECT ... FOR UPDATE inside the creating transaction. Concurrent creates
-- serialise on it and a rollback returns the number. LRs are typed by people at human pace;
-- serialising them costs nothing measurable.
--
-- `series` exists so a financial-year reset ('2026-27') can be added later by inserting a row,
-- not by migrating the table. Today there is exactly one series and it is 'DEFAULT'.
CREATE TABLE lr_counters (
    series     VARCHAR(16) NOT NULL,
    next_value INTEGER     NOT NULL DEFAULT 1,

    CONSTRAINT pk_lr_counters PRIMARY KEY (series),
    CONSTRAINT ck_lr_counters_positive CHECK (next_value >= 1)
);

INSERT INTO lr_counters (series, next_value) VALUES ('DEFAULT', 1);


--changeset vehiclemanagement:25-lorry-receipts
--comment The consignment note itself
--rollback DROP TABLE lorry_receipts;

CREATE TABLE lorry_receipts (
    id         BIGSERIAL,
    -- What is printed and quoted on the phone. Usually 'LR-001', but a caller may supply its
    -- own -- a branch carrying over a book of pre-printed stationery, for instance.
    lr_number  VARCHAR(30) NOT NULL,
    series     VARCHAR(16) NOT NULL DEFAULT 'DEFAULT' REFERENCES lr_counters (series),
    seq_no     INTEGER     NOT NULL,
    lr_date    DATE        NOT NULL,
    status     VARCHAR(12) NOT NULL DEFAULT 'BOOKED',

    -- ── the parties. Link for navigation, text for the document. ────────────────────────
    consignor_id     BIGINT REFERENCES consignors (id) ON DELETE SET NULL,
    consignor_name   VARCHAR(160) NOT NULL,
    consignor_mobile VARCHAR(10),
    consignee_id     BIGINT REFERENCES consignors (id) ON DELETE SET NULL,
    consignee_name   VARCHAR(160) NOT NULL,
    consignee_mobile VARCHAR(10),

    -- ── where it goes. Cities already exist here; tts had to invent them. ───────────────
    from_city_id BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    from_place   VARCHAR(120) NOT NULL,
    to_city_id   BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    to_place     VARCHAR(120) NOT NULL,

    -- ── what it carries ────────────────────────────────────────────────────────────────
    goods_type_id     BIGINT REFERENCES goods_types (id) ON DELETE SET NULL,
    goods_description VARCHAR(500),
    weight_kg         NUMERIC(10,2),
    packages          INTEGER,

    -- ── which truck, and who drove it ──────────────────────────────────────────────────
    vehicle_id     BIGINT REFERENCES vehicles (id) ON DELETE SET NULL,
    -- NOT a foreign key to vehicles.registration_number, and not validated against it: an LR
    -- may legitimately name a hired truck that is not in our register at all.
    vehicle_number VARCHAR(20),
    body_type_id   BIGINT REFERENCES body_types (id) ON DELETE SET NULL,
    vehicle_type   VARCHAR(80),
    driver_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    driver_name    VARCHAR(128),
    driver_mobile  VARCHAR(10),

    -- ── money ──────────────────────────────────────────────────────────────────────────
    freight_charges   NUMERIC(12,2) NOT NULL DEFAULT 0,
    loading_charges   NUMERIC(12,2) NOT NULL DEFAULT 0,
    unloading_charges NUMERIC(12,2) NOT NULL DEFAULT 0,
    other_charges     NUMERIC(12,2) NOT NULL DEFAULT 0,
    advance           NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Generated, for the same reason vehicles.capacity_tons is: the UI sorts and filters on
    -- "who owes us money", and a total computed in Java cannot appear in an ORDER BY.
    total_charges     NUMERIC(12,2) NOT NULL
        GENERATED ALWAYS AS (freight_charges + loading_charges + unloading_charges
                             + other_charges) STORED,
    balance           NUMERIC(12,2) NOT NULL
        GENERATED ALWAYS AS (freight_charges + loading_charges + unloading_charges
                             + other_charges - advance) STORED,

    special_instructions VARCHAR(500),
    notes                VARCHAR(2000),

    -- ── cancellation. tts DELETEs an LR; this one keeps it. ────────────────────────────
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(500),

    created_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_lorry_receipts PRIMARY KEY (id),
    CONSTRAINT uq_lr_number UNIQUE (lr_number),
    -- Per series, not globally: a financial-year reset restarts at 1 and must not collide with
    -- last year's row.
    CONSTRAINT uq_lr_series_seq UNIQUE (series, seq_no),

    CONSTRAINT ck_lr_status CHECK (status IN ('BOOKED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')),
    -- Stated as an equality between two booleans, the same shape ck_intake_vehicle uses, so
    -- "cancelled with no record of when" and "a cancellation date on a live LR" are both
    -- unrepresentable rather than merely discouraged.
    CONSTRAINT ck_lr_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),

    CONSTRAINT ck_lr_charges CHECK (freight_charges >= 0 AND loading_charges >= 0
                                    AND unloading_charges >= 0 AND other_charges >= 0
                                    AND advance >= 0),
    -- Written against the base columns rather than total_charges: PostgreSQL will not let a
    -- CHECK reference a generated column.
    CONSTRAINT ck_lr_advance CHECK (advance <= freight_charges + loading_charges
                                    + unloading_charges + other_charges),
    CONSTRAINT ck_lr_weight   CHECK (weight_kg IS NULL OR weight_kg >= 0),
    CONSTRAINT ck_lr_packages CHECK (packages IS NULL OR packages >= 0),
    CONSTRAINT ck_lr_parties  CHECK (btrim(consignor_name) <> '' AND btrim(consignee_name) <> ''),
    CONSTRAINT ck_lr_places   CHECK (btrim(from_place) <> '' AND btrim(to_place) <> ''),
    CONSTRAINT ck_lr_mobiles  CHECK (
        (consignor_mobile IS NULL OR consignor_mobile ~ '^[6-9][0-9]{9}$')
        AND (consignee_mobile IS NULL OR consignee_mobile ~ '^[6-9][0-9]{9}$')
        AND (driver_mobile  IS NULL OR driver_mobile  ~ '^[6-9][0-9]{9}$'))
);

CREATE TRIGGER trg_lorry_receipts_updated BEFORE UPDATE ON lorry_receipts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The list is a register read newest first, and filtered by status far more than anything else.
CREATE INDEX idx_lr_date_desc  ON lorry_receipts (lr_date DESC, seq_no DESC);
CREATE INDEX idx_lr_status     ON lorry_receipts (status, lr_date DESC);
CREATE INDEX idx_lr_vehicle    ON lorry_receipts (vehicle_id)   WHERE vehicle_id IS NOT NULL;
CREATE INDEX idx_lr_driver     ON lorry_receipts (driver_user_id) WHERE driver_user_id IS NOT NULL;
CREATE INDEX idx_lr_consignor  ON lorry_receipts (consignor_id) WHERE consignor_id IS NOT NULL;
CREATE INDEX idx_lr_consignee  ON lorry_receipts (consignee_id) WHERE consignee_id IS NOT NULL;
-- "Who still owes us" is the report anyone actually runs, and it only concerns open LRs.
CREATE INDEX idx_lr_open_balance ON lorry_receipts (balance DESC)
    WHERE status IN ('BOOKED', 'IN_TRANSIT') AND balance > 0;

COMMENT ON TABLE lorry_receipts IS
    'Consignment notes. Every master link is nullable ON DELETE SET NULL and mirrored by a text '
    'snapshot: the link is for navigation, the snapshot IS the document and must not change '
    'when the directory does.';
COMMENT ON COLUMN lorry_receipts.vehicle_number IS
    'The truck as printed. Not validated against vehicles.registration_number -- an LR may name '
    'a hired truck that was never in our register.';

--liquibase formatted sql
--
-- The whole schema, as formatted SQL rather than an XML or YAML changelog.
--
-- That is a deliberate choice, not laziness: almost nothing here is expressible in
-- Liquibase's database-agnostic tags. Stored generated columns, composite foreign keys over
-- them, UNIQUE ... NULLS NOT DISTINCT, partial indexes, views and a plpgsql trigger function
-- would all end up wrapped in <sql> blocks anyway -- at which point the XML adds ceremony and
-- an opportunity to mistranscribe working DDL, and takes away nothing.
--
-- The trade is real and worth stating: this changelog is PostgreSQL-only. The schema already
-- was (num_nonnulls, ~, GENERATED ALWAYS AS ... STORED), so no portability is being given up
-- that existed.
--
-- Rollback is declared per changeset. Liquibase cannot infer it for raw SQL, and a changeset
-- with no rollback silently blocks `liquibase rollback` for everything after it.
--
--
-- Liquibase owns this schema outright and Hibernate only validates against it. That is
-- deliberate:
-- generated columns, composite foreign keys, partial and NULLS NOT DISTINCT unique
-- indexes and two views are all things Hibernate's DDL generation cannot express, and
-- several invariants here exist precisely so they hold against writers that are not
-- Hibernate -- psql, a data migration, a native UPDATE.
--
-- Every constraint is named. An auto-named CHECK cannot be dropped by a later
-- "ALTER TABLE ... DROP CONSTRAINT IF EXISTS", and the bounds below will meet an exotic
-- vehicle one day.
--
-- Requires PostgreSQL 15+ (UNIQUE ... NULLS NOT DISTINCT).



-- splitStatements:false because the plpgsql body is delimited by $$ and is full of
-- semicolons -- Liquibase's default splitter would cut the function in half.
--changeset vehiclemanagement:01-updated-at-function splitStatements:false
--rollback DROP FUNCTION IF EXISTS set_updated_at();
--comment updated_at, maintained by the database
-- ── updated_at, maintained by the database ──────────────────────────────────────
-- Not Hibernate's @UpdateTimestamp: that fires only for ORM flushes, and this
-- application prefers native SQL, which would silently leave the column stale.
-- One writer, not two racing ones.
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;



--changeset vehiclemanagement:02-geography
--rollback DROP TABLE IF EXISTS cities; DROP TABLE IF EXISTS states;
--comment Geography
-- ── Geography ───────────────────────────────────────────────────────────────────

CREATE TABLE states (
    id   BIGSERIAL,
    code VARCHAR(3)  NOT NULL,
    name VARCHAR(80) NOT NULL,

    CONSTRAINT pk_states   PRIMARY KEY (id),
    CONSTRAINT uq_states_code UNIQUE (code),
    CONSTRAINT uq_states_name UNIQUE (name)
);

CREATE TABLE cities (
    id         BIGSERIAL,
    state_id   BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    -- lower(name); the de-duplication and lookup key
    name_key   VARCHAR(100) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_cities PRIMARY KEY (id),
    -- deleting a state that still has cities is a mistake, not an intention
    CONSTRAINT fk_cities_state FOREIGN KEY (state_id) REFERENCES states (id) ON DELETE RESTRICT,
    -- per state, not global: several states have a Sagar
    CONSTRAINT uq_cities_state_key UNIQUE (state_id, name_key),
    -- the target of the composite FKs on the location tables, which is what makes
    -- "Nagpur, Gujarat" referentially impossible
    CONSTRAINT uq_cities_id_state  UNIQUE (id, state_id)
);
CREATE INDEX idx_cities_key ON cities (name_key);

CREATE TRIGGER trg_cities_updated BEFORE UPDATE ON cities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



--changeset vehiclemanagement:03-body-types
--rollback DROP TABLE IF EXISTS body_types;
--comment Body type master
-- ── Body type master ────────────────────────────────────────────────────────────

CREATE TABLE body_types (
    id         BIGSERIAL,
    name       VARCHAR(80) NOT NULL,
    name_key   VARCHAR(80) NOT NULL,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_body_types      PRIMARY KEY (id),
    CONSTRAINT uq_body_types_key  UNIQUE (name_key)
);

CREATE TRIGGER trg_body_types_updated BEFORE UPDATE ON body_types
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



--changeset vehiclemanagement:04-users
--rollback DROP TABLE IF EXISTS users;
--comment People
-- ── People ──────────────────────────────────────────────────────────────────────
-- One table for both the directory and the login accounts. A driver who never signs in
-- is a row with NULL username and NULL password_hash; staff who do sign in have both.

CREATE TABLE users (
    id            BIGSERIAL,
    name          VARCHAR(120) NOT NULL,
    -- normalised to 10 digits, and the directory's natural key
    mobile        VARCHAR(10)  NOT NULL,
    alt_mobile    VARCHAR(10),
    user_type     VARCHAR(16)  NOT NULL,
    email         VARCHAR(255),
    username      VARCHAR(64),
    password_hash VARCHAR(255),
    city_id       BIGINT,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    notes         VARCHAR(2000),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_users          PRIMARY KEY (id),
    CONSTRAINT uq_users_mobile   UNIQUE (mobile),
    -- NULLs are distinct here, deliberately: any number of drivers may have no login.
    -- Never make this NULLS NOT DISTINCT -- it would permit exactly one of them.
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT fk_users_city     FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE SET NULL,

    CONSTRAINT ck_users_mobile     CHECK (mobile ~ '^[6-9][0-9]{9}$'),
    CONSTRAINT ck_users_alt_mobile CHECK (alt_mobile IS NULL OR alt_mobile ~ '^[6-9][0-9]{9}$'),
    CONSTRAINT ck_users_type       CHECK (user_type IN ('DRIVER', 'OWNER', 'BOTH', 'STAFF', 'ADMIN')),
    -- a login has both halves or neither: a username nobody can authenticate, or a hash
    -- nobody can reach, is the failure mode of merging people and accounts into one table
    CONSTRAINT ck_users_login_pair CHECK (num_nonnulls(username, password_hash) <> 1)
);
CREATE INDEX idx_users_name ON users (name);
CREATE INDEX idx_users_type ON users (user_type);

CREATE TRIGGER trg_users_updated BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



--changeset vehiclemanagement:05-companies
--rollback DROP TABLE IF EXISTS companies;
--comment Companies
-- ── Companies ───────────────────────────────────────────────────────────────────

CREATE TABLE companies (
    id                  BIGSERIAL,
    name                VARCHAR(160) NOT NULL,
    name_key            VARCHAR(160) NOT NULL,
    mobile              VARCHAR(10),
    email               VARCHAR(255),
    gstin               VARCHAR(15),
    address             VARCHAR(300),
    -- NOT a preferred location. Named long and ugly so nobody confuses it with
    -- company_x_location.
    head_office_city_id BIGINT,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_companies       PRIMARY KEY (id),
    CONSTRAINT uq_companies_key   UNIQUE (name_key),
    CONSTRAINT uq_companies_gstin UNIQUE (gstin),
    CONSTRAINT fk_companies_city  FOREIGN KEY (head_office_city_id)
                                  REFERENCES cities (id) ON DELETE SET NULL,

    CONSTRAINT ck_companies_mobile CHECK (mobile IS NULL OR mobile ~ '^[6-9][0-9]{9}$'),
    CONSTRAINT ck_companies_gstin  CHECK (gstin IS NULL OR
        gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$')
);

CREATE TRIGGER trg_companies_updated BEFORE UPDATE ON companies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



--changeset vehiclemanagement:06-vehicles
--rollback DROP TABLE IF EXISTS vehicles;
--comment Vehicles
-- ── Vehicles ────────────────────────────────────────────────────────────────────
-- Ownership lives on this row rather than in two link tables. "Exactly one owner, a
-- company or a person, never both" is a single-row invariant, and only a single-row
-- CHECK can actually enforce it: split across two tables, two concurrent transfers each
-- read the other's table before it commits, see nothing, and both succeed. Here they
-- contend on this row's lock instead, and no application code has to remember to.

CREATE TABLE vehicles (
    id                  BIGSERIAL,
    -- canonical form: upper-case, no separators, e.g. MH12AB1234
    registration_number VARCHAR(12)  NOT NULL,
    body_type_id        BIGINT       NOT NULL,
    no_of_axles         SMALLINT,
    no_of_wheels        SMALLINT,
    -- free text by decision: "20 Ton", "50 Ton". No enum, no master.
    capacity            VARCHAR(32),
    -- derived in the database, never by the application: a field the application
    -- maintains can contradict the text it came from, and this codebase prefers native
    -- UPDATEs, which are exactly the writer that would forget to keep them in step.
    -- The outer parentheses are load-bearing and the inner group MUST be non-capturing:
    -- substring(x from pattern) returns the first PARENTHESISED subexpression when the
    -- pattern has one, not the whole match. Written as '[0-9]+(\.[0-9]+)?' this silently
    -- yields '.5' for '22.5 MT' -- half a tonne for a 22.5-tonne truck.
    capacity_tons       NUMERIC(6,2) GENERATED ALWAYS AS (
                            NULLIF(substring(capacity from '([0-9]+(?:\.[0-9]+)?)'), '')::numeric
                        ) STORED,
    length_ft           NUMERIC(5,2),
    owner_company_id    BIGINT,
    owner_user_id       BIGINT,
    -- read-only discriminator. Exists so vehicle_x_location can carry a composite FK
    -- that makes a preferred location on a company-owned vehicle impossible.
    is_company_owned    BOOLEAN GENERATED ALWAYS AS (owner_company_id IS NOT NULL) STORED,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               VARCHAR(2000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_vehicles     PRIMARY KEY (id),
    CONSTRAINT uq_vehicles_reg UNIQUE (registration_number),

    -- RESTRICT, and SET NULL is not an option: nulling either owner column would leave
    -- num_nonnulls(...) = 0 and violate the CHECK below, surfacing as a baffling check
    -- violation instead of a clear referential one. Deleting a company or an owner means
    -- re-owning its vehicles first -- a fleet does not become ownerless because someone
    -- tidied the directory.
    CONSTRAINT fk_vehicles_owner_company FOREIGN KEY (owner_company_id)
                                         REFERENCES companies (id) ON DELETE RESTRICT,
    CONSTRAINT fk_vehicles_owner_user    FOREIGN KEY (owner_user_id)
                                         REFERENCES users (id) ON DELETE RESTRICT,
    -- retire a body type with is_active = FALSE; do not delete it under a live fleet
    CONSTRAINT fk_vehicles_body_type     FOREIGN KEY (body_type_id)
                                         REFERENCES body_types (id) ON DELETE RESTRICT,

    CONSTRAINT ck_vehicles_one_owner CHECK (num_nonnulls(owner_company_id, owner_user_id) = 1),
    -- the database guarantees only that what is stored is CANONICAL. The full format
    -- regex lives in Normalizer, where relaxing it for the BH series is a commit rather
    -- than a migration on a live database.
    CONSTRAINT ck_vehicles_reg_canon CHECK (registration_number ~ '^[A-Z0-9]{5,12}$'),
    CONSTRAINT ck_vehicles_axles     CHECK (no_of_axles IS NULL OR no_of_axles BETWEEN 1 AND 12),
    -- mod(), not the modulo operator: a bare percent sign in DDL reads as a parameter
    -- placeholder to several drivers and migration tools -- psycopg refuses this file
    -- outright over one, even inside a comment -- and it has to survive all of them
    CONSTRAINT ck_vehicles_wheels    CHECK (no_of_wheels IS NULL OR
                                           (no_of_wheels BETWEEN 2 AND 32 AND mod(no_of_wheels, 2) = 0)),
    CONSTRAINT ck_vehicles_capacity  CHECK (capacity IS NULL OR btrim(capacity) <> ''),
    CONSTRAINT ck_vehicles_length    CHECK (length_ft IS NULL OR length_ft BETWEEN 4 AND 80),

    -- FK targets for the two link tables below
    CONSTRAINT uq_vehicles_id_owner_user    UNIQUE (id, owner_user_id),
    CONSTRAINT uq_vehicles_id_company_owned UNIQUE (id, is_company_owned)
);
CREATE INDEX idx_vehicles_owner_company ON vehicles (owner_company_id);
CREATE INDEX idx_vehicles_owner_user    ON vehicles (owner_user_id);
CREATE INDEX idx_vehicles_body_type     ON vehicles (body_type_id);

CREATE TRIGGER trg_vehicles_updated BEFORE UPDATE ON vehicles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



--changeset vehiclemanagement:07-user-x-company
--rollback DROP TABLE IF EXISTS user_x_company;
--comment Membership
-- ── Membership ──────────────────────────────────────────────────────────────────

CREATE TABLE user_x_company (
    user_id    BIGINT      NOT NULL,
    company_id BIGINT      NOT NULL,
    -- free text: "Fleet manager". Which company a person OWNS is read from
    -- users.user_type, not from here -- see the note in the plan about that being the
    -- weakest joint in the model.
    position   VARCHAR(32),
    is_primary BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_x_company PRIMARY KEY (user_id, company_id),
    CONSTRAINT fk_uxc_user    FOREIGN KEY (user_id)    REFERENCES users (id)     ON DELETE CASCADE,
    CONSTRAINT fk_uxc_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
);
CREATE INDEX idx_uxc_company ON user_x_company (company_id);
-- a person on two companies' books still has one main one
CREATE UNIQUE INDEX uq_uxc_primary ON user_x_company (user_id) WHERE is_primary;



--changeset vehiclemanagement:08-vehicle-x-user
--rollback DROP TABLE IF EXISTS vehicle_x_user;
--comment Who owns and who drives
-- ── Who owns and who drives ─────────────────────────────────────────────────────

CREATE TABLE vehicle_x_user (
    vehicle_id    BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    role          VARCHAR(8)  NOT NULL,
    is_primary    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- non-NULL only on OWNER rows, so MATCH SIMPLE skips fk_vxu_owner_matches entirely
    -- for DRIVER rows
    owner_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN role = 'OWNER' THEN user_id END) STORED,

    CONSTRAINT pk_vehicle_x_user PRIMARY KEY (vehicle_id, user_id, role),
    CONSTRAINT ck_vxu_role       CHECK (role IN ('OWNER', 'DRIVER')),

    -- These two plain FKs are NOT optional. The composite one below is skipped whenever
    -- owner_user_id IS NULL -- which is every DRIVER row -- so without them a driver
    -- assignment could name a vehicle or a user that does not exist.
    CONSTRAINT fk_vxu_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id) ON DELETE CASCADE,
    CONSTRAINT fk_vxu_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE,

    -- An OWNER row may only ever name the vehicle's actual owner. This one FK makes all
    -- of these referentially impossible: an OWNER row on a company-owned vehicle (there
    -- owner_user_id is NULL, so the pair has no target); an OWNER row naming someone
    -- else; a second OWNER row (both would have to equal owner_user_id, and the PK
    -- blocks the duplicate); and an ownership change that strands the old OWNER row
    -- (ON UPDATE NO ACTION rejects it).
    CONSTRAINT fk_vxu_owner_matches FOREIGN KEY (vehicle_id, owner_user_id)
        REFERENCES vehicles (id, owner_user_id) ON DELETE CASCADE ON UPDATE NO ACTION
);
CREATE INDEX idx_vxu_user ON vehicle_x_user (user_id);
CREATE UNIQUE INDEX uq_vxu_primary_driver ON vehicle_x_user (vehicle_id)
    WHERE role = 'DRIVER' AND is_primary;



--changeset vehiclemanagement:09-locations
--rollback DROP TABLE IF EXISTS vehicle_x_location; DROP TABLE IF EXISTS company_x_location;
--comment Preferred locations: a state, optionally narrowed to one city
-- ── Preferred locations: a state, optionally narrowed to one city ───────────────
-- city_id NULL means the whole state. The composite FK to cities (id, state_id) makes a
-- city-in-the-wrong-state impossible, and under MATCH SIMPLE it skips itself when
-- city_id IS NULL, which is exactly the state-only case.
--
-- There is deliberately no second single-column FK on city_id: it would overlap the
-- composite one with a different ON DELETE rule, which is two rules for one delete.
-- The composite alone means deleting a city someone prefers is refused -- correct here,
-- since cities are retired with is_active rather than deleted.

CREATE TABLE company_x_location (
    id         BIGSERIAL,
    company_id BIGINT      NOT NULL,
    state_id   BIGINT      NOT NULL,
    city_id    BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_company_x_location PRIMARY KEY (id),
    CONSTRAINT fk_cxl_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE,
    CONSTRAINT fk_cxl_state   FOREIGN KEY (state_id)   REFERENCES states (id),
    CONSTRAINT fk_cxl_city_in_state FOREIGN KEY (city_id, state_id)
                                    REFERENCES cities (id, state_id),
    -- NULLS NOT DISTINCT: "anywhere in Maharashtra" is listed once, not five times
    CONSTRAINT uq_cxl UNIQUE NULLS NOT DISTINCT (company_id, state_id, city_id)
);
CREATE INDEX idx_cxl_state ON company_x_location (state_id);
CREATE INDEX idx_cxl_city  ON company_x_location (city_id);


CREATE TABLE vehicle_x_location (
    id               BIGSERIAL,
    vehicle_id       BIGINT      NOT NULL,
    state_id         BIGINT      NOT NULL,
    city_id          BIGINT,
    -- always FALSE (see the CHECK); exists only so the composite FK below can compare it
    -- against the vehicle's own discriminator
    is_company_owned BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_vehicle_x_location PRIMARY KEY (id),
    CONSTRAINT fk_vxl_state FOREIGN KEY (state_id) REFERENCES states (id),
    CONSTRAINT fk_vxl_city_in_state FOREIGN KEY (city_id, state_id)
                                    REFERENCES cities (id, state_id),
    CONSTRAINT uq_vxl UNIQUE NULLS NOT DISTINCT (vehicle_id, state_id, city_id),

    -- A vehicle's own preferred locations may exist only while no company owns it. Both
    -- columns are NOT NULL, so unlike vehicle_x_user this composite FK is never skipped
    -- and covers vehicle_id on its own. ON UPDATE NO ACTION means selling the vehicle to
    -- a company is REJECTED while these rows exist, so forgetting to clear them is
    -- impossible rather than merely discouraged.
    CONSTRAINT ck_vxl_not_company_owned CHECK (is_company_owned = FALSE),
    CONSTRAINT fk_vxl_vehicle FOREIGN KEY (vehicle_id, is_company_owned)
        REFERENCES vehicles (id, is_company_owned) ON DELETE CASCADE ON UPDATE NO ACTION
);
CREATE INDEX idx_vxl_state ON vehicle_x_location (state_id);
CREATE INDEX idx_vxl_city  ON vehicle_x_location (city_id);



--changeset vehiclemanagement:10-views
--rollback DROP VIEW IF EXISTS vehicle_effective_locations; DROP VIEW IF EXISTS vehicle_x_company;
--comment Views
-- ── Views ───────────────────────────────────────────────────────────────────────

-- The table name from the original design, over the column that actually holds the fact.
-- Read-only on purpose: ownership is set through vehicles.
CREATE VIEW vehicle_x_company AS
SELECT id AS vehicle_id, owner_company_id AS company_id, created_at
  FROM vehicles
 WHERE owner_company_id IS NOT NULL;

COMMENT ON VIEW vehicle_x_company IS
    'Read-only view over vehicles.owner_company_id. Ownership is set by updating '
    'vehicles, never by inserting here -- see ck_vehicles_one_owner.';

-- The company-else-own fallback, expressed once, in SQL. A Java-side resolver could
-- answer "where does this vehicle run?" but not "which vehicles run to Nagpur?" without
-- loading every vehicle, and two implementations of one rule drift.
CREATE VIEW vehicle_effective_locations AS
SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'COMPANY' AS source
  FROM vehicles v
  JOIN company_x_location l ON l.company_id = v.owner_company_id
 WHERE v.owner_company_id IS NOT NULL
UNION ALL
SELECT v.id AS vehicle_id, l.state_id, l.city_id, 'VEHICLE' AS source
  FROM vehicles v
  JOIN vehicle_x_location l ON l.vehicle_id = v.id
 WHERE v.owner_company_id IS NULL;

COMMENT ON VIEW vehicle_effective_locations IS
    'A company-owned vehicle serves its company''s locations; an independent one serves '
    'its own. A company with no locations serves nowhere -- it does NOT fall back.';

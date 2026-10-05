--liquibase formatted sql
--
-- A pick list of capacities, and three more things a CSR learns on the call.
--
-- **The capacity master is a PICK LIST, not a foreign key.** `vehicles.capacity` stays free text
-- and nothing here points at it. That is deliberate and worth defending:
--
--   * `vehicles.capacity_tons` is a GENERATED column derived from the text by regex. Turning
--     capacity into an FK would mean either dropping that column or joining to compute it, and
--     it is what every tonnage search in the system sorts and filters on.
--   * The first third-party import will arrive saying "16T", "16 Ton", "16.5 MT" and "Sixteen".
--     A free-text column takes all of them and the generated column still yields 16, 16, 16.5
--     and null. An FK rejects the lot, and the import becomes a data-cleaning project.
--
-- So this table answers "what should the dropdown offer", and the answer to "what does this
-- truck carry" stays where it was. A CSR picks from the list; a migration can still write
-- anything.


--changeset vehiclemanagement:17-capacities
--comment Pick list of common capacities for the intake and vehicle forms
--rollback DROP TABLE capacities;

CREATE TABLE capacities (
    id         BIGSERIAL,
    -- Exactly what goes into vehicles.capacity when this is chosen. Stored as the label so the
    -- two never drift: there is no formatting step between picking and saving.
    label      VARCHAR(32)  NOT NULL,
    -- What the label is worth in tonnes. NOT used by vehicles.capacity_tons -- that is still
    -- derived from the text -- but it is what makes the list sort sensibly rather than
    -- alphabetically, where "9 Ton" falls between "16 Ton" and "25 Ton".
    tons       NUMERIC(6,2) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_capacities PRIMARY KEY (id),
    CONSTRAINT uq_capacities_label UNIQUE (label),
    CONSTRAINT ck_capacities_label CHECK (btrim(label) <> ''),
    CONSTRAINT ck_capacities_tons  CHECK (tons > 0)
);

CREATE TRIGGER trg_capacities_updated BEFORE UPDATE ON capacities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE capacities IS
    'Pick list for the capacity field. NOT a foreign key: vehicles.capacity stays free text so '
    'the generated capacity_tons keeps working and imports with odd spellings still load.';

-- The rungs actually used in Indian road freight, smallest first.
INSERT INTO capacities (label, tons) VALUES
    ('1 Ton', 1), ('2 Ton', 2), ('3 Ton', 3), ('5 Ton', 5), ('7 Ton', 7), ('9 Ton', 9),
    ('10 Ton', 10), ('12 Ton', 12), ('16 Ton', 16), ('18 Ton', 18), ('21 Ton', 21),
    ('25 Ton', 25), ('28 Ton', 28), ('30 Ton', 30), ('35 Ton', 35), ('40 Ton', 40),
    ('45 Ton', 45), ('50 Ton', 50);


--changeset vehiclemanagement:18-intake-call-details
--comment Body type, capacity and preferred locations, learned on the call
--rollback ALTER TABLE vehicle_intake DROP COLUMN body_type_id,
--rollback                            DROP COLUMN capacity_id,
--rollback                            DROP COLUMN edited_places;

ALTER TABLE vehicle_intake
    -- One column each, not a reported/matched/edited trio. Both are ids into their masters: the
    -- mobile app sends the id at upload and the CSR overwrites it on the call. RESTRICT,
    -- matching vehicles.body_type_id: retiring one must not silently blank it on work in
    -- progress. (vehicles.capacity itself stays free text; completion writes the label.)
    ADD COLUMN body_type_id BIGINT,
    ADD COLUMN capacity_id  BIGINT,
    -- [{"state_id": 12, "city_id": 340}, ...]; a null city means the whole state.
    --
    -- JSONB rather than a child table, because these are not the vehicle's locations yet and
    -- must not be mistaken for them. `vehicle_x_location` and `company_x_location` are the real
    -- thing, written by VehicleService at completion, where the rule about WHICH of the two a
    -- place belongs in gets applied. This column is a CSR's notes until then, and giving notes
    -- their own table with the same shape as the real one is how the two get confused.
    ADD COLUMN edited_places       JSONB;

ALTER TABLE vehicle_intake
    ADD CONSTRAINT fk_intake_body_type
        FOREIGN KEY (body_type_id) REFERENCES body_types (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_intake_capacity
        FOREIGN KEY (capacity_id) REFERENCES capacities (id) ON DELETE RESTRICT;

COMMENT ON COLUMN vehicle_intake.edited_places IS
    'Where the driver says the truck runs, as [{state_id, city_id|null}]. Notes, not locations: '
    'the real rows are written to company_x_location or vehicle_x_location at completion, and '
    'which of the two depends on who ends up owning the truck.';

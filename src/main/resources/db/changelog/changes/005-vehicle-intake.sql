--liquibase formatted sql
--
-- Photos from the road, waiting to become vehicles.
--
-- A field executive photographs a truck; an OCR worker reads what it can; a CSR rings the
-- driver and fills in the rest. This table holds that work in progress, and it is modelled on
-- FreightDesk's `trucks` table, which does the same job.
--
-- **Why this is not a column on `vehicles`.** A vehicle row cannot exist yet. `vehicles`
-- requires a registration number that is NOT NULL, UNIQUE and matches ^[A-Z0-9]{5,12}$, a
-- body type, and exactly one owner (ck_vehicles_one_owner). At upload time there is no plate,
-- no body type and nobody who owns it -- and OCR may return nothing, or something already
-- registered. Adding a "pending" status to `vehicles` would mean relaxing every one of those
-- constraints, which are the guarantees the whole schema is built on. FreightDesk needs no such
-- split because its `trucks` table has exactly two required columns and no fleet register to
-- keep clean.
--
-- **Two status columns, not one.** This is the one design idea worth copying wholesale from
-- FreightDesk, whose own comment calls them independent dimensions:
--
--   processing_status  what the MACHINE has done   QUEUED -> PROCESSING -> DONE | FAILED
--   review_status      what the HUMAN has decided  PENDING -> COMPLETED | DISCARDED
--
-- They move on their own clocks and answer different questions. "Has OCR finished?" and "has
-- anyone rung this driver?" are not points on one line, and an earlier draft of this table that
-- made them one column could not express the ordinary case of a FAILED photo a CSR works
-- anyway -- OCR read nothing, but a human can see the plate perfectly well.
--
--   upload --> QUEUED --claim--> PROCESSING --> DONE ---+
--                 ^                  |                  +--> the CSR worklist, review_status
--                 |                  +--> FAILED -------+     PENDING --> COMPLETED | DISCARDED
--                 |                        |
--                 +---- a human pressing --+
--                       "Read again"
--
-- **There is no automatic retry.** One failed attempt is final: the row goes to FAILED and no
-- worker will look at it again. The only way back into the queue is a person deciding so, which
-- is why the arrow above is drawn from a human and not from the worker. A photo the engine
-- cannot read is not going to become readable on a second pass, and a CSR looking at the picture
-- settles it in seconds -- so the cost of a retry was three delays to reach the same verdict.


--changeset vehiclemanagement:14-vehicle-intake splitStatements:false
--comment Field-report staging: photos awaiting OCR and then a CSR
--rollback DROP TRIGGER IF EXISTS trg_vehicle_intake_updated ON vehicle_intake;
--rollback DROP TABLE vehicle_intake;

CREATE TABLE vehicle_intake (
    id                BIGSERIAL,

    -- -- What the field executive sent ----------------------------------------------------
    -- All optional and all unvalidated on purpose: this is a claim, not a fact. It is kept
    -- apart from the ocr_* columns below so the two can be compared, which is how FreightDesk
    -- decides whether a paid report is trustworthy. Neither side is authoritative until a CSR
    -- says so.
    reported_plate    VARCHAR(32),
    reported_mobile   VARCHAR(10),
    reported_company  VARCHAR(160),
    -- The reporter, not the truck: a display name, and their own number, snapshot at upload.
    reported_by       VARCHAR(128),
    reporter_mobile   VARCHAR(10),
    captured_at       TIMESTAMPTZ,
    location          VARCHAR(255),
    latitude          DOUBLE PRECISION,
    longitude         DOUBLE PRECISION,

    -- -- Where the photos are -------------------------------------------------------------
    -- Storage keys in the object store, e.g. ['intake/2026-09-24/9f3c.../0.jpg'].
    --
    -- NOTHING parses a key. That discipline is what let FreightDesk change its object layout
    -- twice with no migration: a photo is always fetched by the key recorded here, never by
    -- rebuilding one from a pattern.
    --
    -- NOT NULL, and the API writes every photo to the store BEFORE inserting this row. That
    -- ordering matters now that the worker polls this table directly: FreightDesk inserts the
    -- row first and attaches keys a moment later, which is harmless when an in-process queue
    -- hands the id over at the end, but leaves a window in which a polling worker can claim a
    -- row whose photos do not exist yet and fail it for "photos no longer available".
    image_keys        JSONB        NOT NULL,

    -- -- Dimension 1: the OCR job ---------------------------------------------------------
    processing_status VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    processing_error  VARCHAR(500),
    processed_at      TIMESTAMPTZ,
    -- `claimed_at` is what lets a crashed worker's rows be reclaimed without a restart, which
    -- FreightDesk cannot do: there, a crash strands rows until the process comes back.
    --
    -- Reclaiming is NOT a retry. A worker that was killed mid-job never reached a verdict on
    -- the photo, so its row returns to QUEUED with `attempts` untouched. Without that, every
    -- deploy would permanently fail whatever happened to be in flight.
    claimed_at        TIMESTAMPTZ,
    -- How many times anyone has tried to read these photos. There is NO automatic retry: one
    -- failed attempt marks the row FAILED and nothing picks it up again. This counts the times
    -- a human pressed Read again, so a row that has been fought over is visible as such.
    attempts          SMALLINT     NOT NULL DEFAULT 0,

    -- -- What OCR read --------------------------------------------------------------------
    -- All nullable: every one is a suggestion, and "read nothing" is an ordinary outcome for a
    -- photo taken at speed from across a road. Measured on 20 real field photos, a plate comes
    -- back on 5 of them.
    ocr_plate         VARCHAR(32),
    -- EVERY number read off the truck, best-read first -- not just one.
    --
    -- A truck routinely carries several, painted in a row: the owner's, the driver's, the
    -- transport office's. A single column meant that if the first did not answer, the second
    -- was lost even though it was legible in the same photo. A list, because "how many numbers
    -- are on a truck" is not a question with one answer, and because a CSR chooses between them
    -- rather than being handed a winner.
    ocr_mobiles       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ocr_company       VARCHAR(160),
    ocr_confidence    VARCHAR(8),
    -- Every candidate and every body text the engine returned, so a wrong read can be
    -- explained after the fact rather than argued about.
    ocr_raw           JSONB,

    -- -- Dimension 2: the CSR -------------------------------------------------------------
    review_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reviewed_by       VARCHAR(128),
    reviewed_at       TIMESTAMPTZ,
    review_note       VARCHAR(500),
    -- The vehicle this became, once a CSR completed it.
    vehicle_id        BIGINT,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_vehicle_intake PRIMARY KEY (id),

    -- SET NULL rather than RESTRICT: deleting a vehicle should not be blocked by the photo that
    -- produced it. The row stays as a record that the work was done.
    CONSTRAINT fk_intake_vehicle FOREIGN KEY (vehicle_id)
                                 REFERENCES vehicles (id) ON DELETE SET NULL,

    CONSTRAINT ck_intake_processing CHECK (processing_status IN
        ('QUEUED', 'PROCESSING', 'DONE', 'FAILED')),

    CONSTRAINT ck_intake_review CHECK (review_status IN
        ('PENDING', 'COMPLETED', 'DISCARDED')),

    -- Completed means "a vehicle came out of this", and nothing else may claim to have produced
    -- one. Written as an equality between two booleans so BOTH halves hold: COMPLETED without a
    -- vehicle, and a vehicle on a row nobody has reviewed, are equally impossible.
    CONSTRAINT ck_intake_vehicle CHECK ((review_status = 'COMPLETED') = (vehicle_id IS NOT NULL)),

    -- An upload with no photos is not an intake; it is a mistake worth refusing at the door.
    CONSTRAINT ck_intake_has_images CHECK (jsonb_array_length(image_keys) > 0),

    CONSTRAINT ck_intake_attempts CHECK (attempts >= 0)
);

-- The worker's claim is `WHERE processing_status = 'QUEUED' ORDER BY created_at ... SKIP
-- LOCKED`. Partial, because QUEUED is the small transient tail of the table: everything else
-- has already been processed, and indexing those rows would be paying to find what is never
-- looked for.
CREATE INDEX idx_intake_queued ON vehicle_intake (created_at)
    WHERE processing_status = 'QUEUED';


-- The CSR worklist is "machine has finished, human has not": every tab in the UI is a slice of
-- this pair, ordered by age.
CREATE INDEX idx_intake_worklist ON vehicle_intake (review_status, processing_status, created_at);

-- Reclaiming a crashed worker's rows scans PROCESSING by age.
CREATE INDEX idx_intake_claimed ON vehicle_intake (claimed_at)
    WHERE processing_status = 'PROCESSING';

COMMENT ON TABLE vehicle_intake IS
    'Field photo reports awaiting OCR and then a CSR. Deliberately separate from vehicles: a '
    'vehicle needs a canonical unique plate, a body type and exactly one owner, none of which '
    'exist until a CSR has finished the call. processing_status is the machine dimension, '
    'review_status the human one; they are independent.';

CREATE TRIGGER trg_vehicle_intake_updated BEFORE UPDATE ON vehicle_intake
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

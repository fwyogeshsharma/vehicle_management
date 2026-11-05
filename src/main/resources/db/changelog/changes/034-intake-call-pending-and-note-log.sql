--liquibase formatted sql
--
-- Two additions to the photo-intake worklist.
--
-- call_pending: a tick the CSR sets on a row meaning "I still have to ring this driver". It is
-- the CSR's own flag, written by this service only; the OCR worker neither reads nor writes it.
--
-- intake_notes: remarks become a LOG instead of one editable text box. Each save that carries
-- a remark adds a row with its date and who wrote it, rows are never edited, and the form shows
-- them as read-only lines above an empty box for the next one. vehicle_intake.notes (changeset
-- 028) stays in the table so the migration is reversible, but nothing writes it any more; any
-- remark already in it is copied here as the first entry.
--
-- ON DELETE CASCADE: discarding an intake deletes the row and its photos, and its remarks go
-- with it. A COMPLETED intake is never deleted, so a vehicle's provenance keeps its notes.

--changeset vehiclemanagement:38-intake-call-pending-and-note-log
--comment vehicle_intake.call_pending and the intake_notes log
--rollback DROP TABLE intake_notes;
--rollback ALTER TABLE vehicle_intake DROP COLUMN call_pending;

ALTER TABLE vehicle_intake
    ADD COLUMN call_pending BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN vehicle_intake.call_pending IS
    'The CSR still has to ring the driver. Owned by vehicleManagement, not the OCR worker.';

CREATE TABLE intake_notes (
    id         BIGSERIAL,
    intake_id  BIGINT        NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_intake_notes PRIMARY KEY (id),
    CONSTRAINT fk_intake_notes_intake FOREIGN KEY (intake_id)
        REFERENCES vehicle_intake (id) ON DELETE CASCADE,
    CONSTRAINT ck_intake_notes_body CHECK (length(btrim(body)) > 0)
);

CREATE INDEX idx_intake_notes_intake ON intake_notes (intake_id, created_at);

INSERT INTO intake_notes (intake_id, body, created_by, created_at)
SELECT id, left(btrim(notes), 1000), edited_by, COALESCE(edited_at, created_at)
FROM vehicle_intake
WHERE notes IS NOT NULL AND btrim(notes) <> '';

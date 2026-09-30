package com.vehiclemanagement.service;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Where photo bytes live.
 *
 * <p><b>Not in PostgreSQL.</b> A phone photo is several megabytes; a few thousand of them in the
 * same database that serves the fleet register would dominate its size and its backups, and
 * every read would drag the bytes through a connection. The database holds a key, the bytes sit
 * in an object store. This mirrors FreightDesk's {@code pipeline/storage.py}, which made the
 * same call and offers the same two backends behind the same four methods.
 *
 * <p><b>Nothing parses a key.</b> A photo is always fetched by the string recorded on the intake
 * row, never by rebuilding one from a pattern — which is precisely what let FreightDesk change
 * its object layout twice without a migration, and what lets one key resolve against local disk
 * here and a GCS bucket in production. {@link #keyFor} is the only place the shape is decided,
 * and it is write-only knowledge.
 *
 * <p><b>Two readers.</b> The Python OCR worker reads these same keys out of the same bucket with
 * its own credentials — see {@code vehicleManagementOcr/ocr/storage.py}, which is a port of this
 * interface. The key layout is therefore a contract between two repositories; change it in one
 * and old keys still resolve (nothing parses them), but new ones must agree.
 */
public interface ImageStore {

    /**
     * The key for one photo of one upload.
     *
     * <p>Date first so a day's uploads share a prefix and can be lifecycled, archived or deleted
     * in one operation — one {@code gcloud storage rm -r} rather than hand-picking hundreds of
     * per-report folders. UTC, to match the objects' own creation timestamps.
     *
     * <p>The middle segment is an {@code uploadId} — a UUID minted by the caller — and
     * <b>deliberately not the intake row's id</b>. The bytes are written before the row exists,
     * because a worker polling {@code vehicle_intake} directly must never be able to see a
     * QUEUED row whose photos have not landed yet. FreightDesk inserts first and attaches keys
     * a moment later, which is safe there only because an in-process queue hands the id over at
     * the very end.
     */
    default String keyFor(String uploadId, int index, String extension) {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        return "intake/" + day + "/" + uploadId + "/" + index + extension;
    }

    void put(String key, byte[] bytes, String contentType);

    /** The bytes, or a 404 — a key that no longer resolves is gone, not temporarily missing. */
    byte[] get(String key);

    boolean exists(String key);
}

package com.vehiclemanagement.domain;

/**
 * What a <b>human</b> has decided about a field report.
 *
 * <pre>
 *   PENDING --+--&gt; COMPLETED   (the CSR rang the driver; a Vehicle now exists)
 *             +--&gt; DISCARDED   (not a truck, unreadable, or already registered)
 * </pre>
 *
 * <p>Independent of {@link ProcessingStatus}. A row is PENDING from the moment it is uploaded,
 * including while OCR is still running — "nobody has looked at this yet" is true then too.
 *
 * <p>COMPLETED is the only value that may carry a {@code vehicle_id}, and it must:
 * {@code ck_intake_vehicle} states it as an equality between two booleans so that both halves
 * hold at once.
 */
public enum ReviewStatus {

    /** Nobody has decided yet. Every row starts here. */
    PENDING,

    /** A CSR finished the call and created the vehicle. Terminal. */
    COMPLETED,

    /** A CSR rejected it. Terminal. The reason is in {@code review_note}. */
    DISCARDED
}

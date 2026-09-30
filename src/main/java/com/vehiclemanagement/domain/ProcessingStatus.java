package com.vehiclemanagement.domain;

/**
 * How far the <b>machine</b> has got with a field report's photos.
 *
 * <pre>
 *   QUEUED --claim--&gt; PROCESSING --+--&gt; DONE
 *      ^                           |
 *      |                           +--&gt; FAILED
 *      |                                  |
 *      +---- a human pressing Read again -+
 * </pre>
 *
 * <p><b>There is no automatic retry.</b> One failed attempt is final; the arrow back into the
 * queue is drawn from a person because only a person can draw it.
 *
 * <p>Deliberately says nothing about whether a human has acted — that is {@link ReviewStatus},
 * and the two are independent. A report can be FAILED and still perfectly workable: OCR read
 * nothing, but a CSR looking at the photo can see the plate. Folding the two axes into one
 * column, as an earlier draft of this table did, makes that ordinary case unrepresentable.
 * FreightDesk keeps them apart for the same reason and its schema comment says so outright.
 *
 * <p><b>Who writes this.</b> Only the OCR worker (vehicleManagementOcr), which polls the table
 * directly over its own PostgreSQL connection — the claim is {@code FOR UPDATE SKIP LOCKED} and
 * lives in that worker's {@code db.py}. This service writes the column in exactly one place,
 * {@code IntakeService.retry}, which is how a human puts a FAILED row back in the queue.
 */
public enum ProcessingStatus {

    /** Photos are in the object store, waiting for a worker. */
    QUEUED,

    /** A worker holds it. Reclaimed automatically if that worker dies still holding it. */
    PROCESSING,

    /** OCR finished — whether or not it actually read anything. */
    DONE,

    /**
     * OCR gave up. Terminal as far as the machine is concerned.
     *
     * <p>Emphatically <b>not</b> terminal for the report: a CSR can complete the row by hand
     * from the photo, which on real field photos is the common case rather than the exotic one.
     */
    FAILED
}

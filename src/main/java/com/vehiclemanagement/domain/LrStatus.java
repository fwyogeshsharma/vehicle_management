package com.vehiclemanagement.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a lorry receipt is in its life.
 *
 * <pre>
 *   BOOKED --&gt; IN_TRANSIT --&gt; DELIVERED
 *      |            |
 *      +------------+--&gt; CANCELLED
 * </pre>
 *
 * <p><b>Transitions are enforced, unlike in tts.</b> There, {@code apply()} writes whatever
 * status arrives in the request, so a delivered consignment can be moved back to booked and a
 * cancelled one revived. Both are corrections somebody makes by accident while editing another
 * field, and neither should be possible on a document a customer has a signed copy of.
 *
 * <p>DELIVERED and CANCELLED are terminal. Undoing one is a deliberate act with a name, not an
 * edit — and there is no endpoint for it, because nobody has said what it should mean.
 */
public enum LrStatus {

    /** Written up; the truck has not left. */
    BOOKED,

    /** On the road. */
    IN_TRANSIT,

    /** Signed for at the other end. Terminal. */
    DELIVERED,

    /** Called off. Terminal, and the row is kept — see {@code ck_lr_cancelled}. */
    CANCELLED;

    private static final Set<LrStatus> OPEN = EnumSet.of(BOOKED, IN_TRANSIT);

    /** Still owed, still countable, still editable. */
    public boolean isOpen() {
        return OPEN.contains(this);
    }

    /** Whether this LR may move to {@code next}. Staying put is always allowed. */
    public boolean canMoveTo(LrStatus next) {
        if (next == null || next == this) {
            return true;
        }
        return switch (this) {
            case BOOKED -> next == IN_TRANSIT || next == DELIVERED || next == CANCELLED;
            // Delivering straight from BOOKED is allowed: a short local run is loaded, driven
            // and signed for before anyone opens the screen again, and forcing a pointless
            // IN_TRANSIT click would only teach people to click it without meaning it.
            case IN_TRANSIT -> next == DELIVERED || next == CANCELLED;
            case DELIVERED, CANCELLED -> false;
        };
    }
}

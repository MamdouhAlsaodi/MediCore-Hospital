package com.mamtrex.hospital.transfer;

/**
 * Bounded transfer lifecycle state machine (specs/005 data-model.md
 * "Transfer" lifecycle; Phase 5 T082):
 *
 * <pre>
 * REQUESTED -&gt; ACCEPTED -&gt; IN_TRANSIT -&gt; COMPLETED
 * REQUESTED -&gt; REJECTED
 * REQUESTED -&gt; CANCELLED
 * ACCEPTED  -&gt; CANCELLED
 * </pre>
 *
 * REJECTED, CANCELLED, and COMPLETED are terminal. Persistence stores the
 * enum name in the bounded {@code status VARCHAR(20)} column.
 */
public enum TransferStatus {
    REQUESTED,
    ACCEPTED,
    IN_TRANSIT,
    COMPLETED,
    REJECTED,
    CANCELLED;

    /**
     * Whether a transition from this status into {@code target} is legal.
     * Self-transitions are never legal; terminal statuses transition to
     * nothing.
     */
    public boolean canTransitionTo(TransferStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case REQUESTED -> target == ACCEPTED || target == REJECTED || target == CANCELLED;
            case ACCEPTED -> target == IN_TRANSIT || target == CANCELLED;
            case IN_TRANSIT -> target == COMPLETED;
            case COMPLETED, REJECTED, CANCELLED -> false;
        };
    }
}

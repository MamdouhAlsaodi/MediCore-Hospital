package com.mamtrex.hospital.transfer;

/**
 * Bounded reservation lifecycle (specs/005 data-model.md
 * {@code TransferBedReservation}; Phase 5 T084): a reservation is ACTIVE
 * from creation, and ACTIVE can move exactly once to CONSUMED (transfer
 * completion) or to RELEASED (rejection/cancellation). CONSUMED and
 * RELEASED are terminal. Persistence stores the enum name in the bounded
 * {@code status VARCHAR(16)} column.
 */
public enum ReservationStatus {
    ACTIVE,
    CONSUMED,
    RELEASED;

    /** Whether this reservation state may still move to {@code target}. */
    public boolean canTransitionTo(ReservationStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return this == ACTIVE && (target == CONSUMED || target == RELEASED);
    }
}

package com.mamtrex.hospital.idempotency;

/**
 * Bounded idempotency-record lifecycle (specs/005 data-model.md
 * {@code IdempotencyRecord}; Phase 5 T086): a record is IN_PROGRESS from
 * its creation and moves exactly once to COMPLETED when the guarded
 * mutation commits. Persistence stores the enum name in the bounded
 * {@code state VARCHAR(16)} column; the check constraint in V7 pins it.
 */
public enum IdempotencyRecordState {
    IN_PROGRESS,
    COMPLETED
}

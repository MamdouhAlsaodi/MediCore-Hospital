package com.mamtrex.hospital.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * The narrow storage port behind {@link IdempotencyService} (Phase 5 T087).
 * The production adapter is the Spring Data {@link IdempotencyRecordRepository};
 * tests substitute an in-memory fake, so the bounded fingerprint/replay/
 * conflict semantics are provable without a database.
 */
public interface IdempotencyRecordGateway {

    /** The lookup by the full normalized unique key. */
    Optional<IdempotencyRecord> findByAssignmentIdAndOperationAndIdempotencyKey(
            UUID assignmentId, String operation, String idempotencyKey);

    /** Persist (create or update) one record. */
    IdempotencyRecord save(IdempotencyRecord record);
}

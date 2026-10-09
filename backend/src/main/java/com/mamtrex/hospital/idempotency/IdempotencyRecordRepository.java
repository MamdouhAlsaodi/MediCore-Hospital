package com.mamtrex.hospital.idempotency;

import com.mamtrex.hospital.auth.ActingAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data adapter for idempotency records (Phase 5 T086). The derived
 * key lookup rides the V7 unique constraint
 * {@code uq_idempotency_records_key} on
 * {@code (assignment_id, operation, idempotency_key)}; that constraint is
 * the race backstop behind the service pre-check.
 */
public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, UUID>, IdempotencyRecordGateway {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActingAssignment a where a.id = :assignmentId")
    Optional<ActingAssignment> lockAssignment(UUID assignmentId);
}

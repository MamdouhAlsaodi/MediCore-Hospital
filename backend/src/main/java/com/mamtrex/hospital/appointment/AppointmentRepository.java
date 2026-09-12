package com.mamtrex.hospital.appointment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /*
     * Branch-scoped workflow reads (docs/plan3.md Task 4): a cross-branch id
     * resolves to empty, indistinguishable from nonexistent.
     */
    Optional<Appointment> findByIdAndBranchId(UUID id, UUID branchId);

    List<Appointment> findByBranchIdOrderByIdAsc(UUID branchId);

    /*
     * Task 9 (docs/plan3.md §4.6) half-open overlap candidates for one
     * professional inside the acting branch:
     * newStart < existingEnd && newEnd > existingStart. scheduledAt and
     * endsAt are the canonical LocalDateTime.toString() values the service
     * writes, so the lexicographic SQL range stays order-safe; adjacency
     * (newStart == existingEnd or newEnd == existingStart) never matches the
     * strict inequalities. Only the already-defined explicit 'cancelled'
     * status is excluded — no cancellation lifecycle is invented — and
     * pre-Task-9 rows without a computable window (null endsAt) are honestly
     * excluded as conflict candidates instead of compared with an invented
     * duration.
     */
    @Query("""
            select count(a) from Appointment a
            where a.branch.id = :branchId and a.professionalId = :professionalId
              and a.status <> 'cancelled' and a.endsAt is not null
              and a.endsAt > :start and a.scheduledAt < :end
            """)
    long countConflicting(@Param("branchId") UUID branchId,
                          @Param("professionalId") String professionalId,
                          @Param("start") String start,
                          @Param("end") String end);
}

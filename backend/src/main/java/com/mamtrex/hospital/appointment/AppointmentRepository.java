package com.mamtrex.hospital.appointment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /*
     * Task 10 dashboard aggregation (docs/plan3.md §4.7): grouped counts
     * restricted to an explicit branch-id set — never a whole-table read.
     * The today window compares the same canonical LocalDateTime.toString()
     * representation the service writes (the established Task 9 range
     * pattern), with the start inclusive and the end exclusive; rows
     * without a parseable canonical value are the legacy seam and stay
     * honestly outside the window instead of being reinterpreted.
     */
    @Query("select a.branch.id as branchId, count(a) as total from Appointment a "
            + "where a.branch.id in :branchIds group by a.branch.id")
    List<BranchMetric> countByBranchIdInGrouped(@Param("branchIds") Collection<UUID> branchIds);

    @Query("select a.branch.id as branchId, count(a) as total from Appointment a "
            + "where a.branch.id in :branchIds and a.scheduledAt >= :windowStart and a.scheduledAt < :windowEnd "
            + "group by a.branch.id")
    List<BranchMetric> countByBranchIdAndScheduledAtRangeGrouped(@Param("branchIds") Collection<UUID> branchIds,
                                                                 @Param("windowStart") String windowStart,
                                                                 @Param("windowEnd") String windowEnd);

    /** One grouped-count tuple shared by the two queries above. */
    interface BranchMetric {
        UUID getBranchId();
        long getTotal();
    }
}

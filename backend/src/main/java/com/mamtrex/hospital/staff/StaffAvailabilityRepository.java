package com.mamtrex.hospital.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Branch-scoped reads over the dated availability intervals
 * (docs/plan3.md Task 9). Every query carries branch, professional, and
 * time criteria — there is no whole-table read on this surface.
 *
 * <p>All interval comparisons are half-open: {@code [startsAt, endsAt)}.
 * The overlap test uses strict inequalities so exactly-adjacent intervals
 * never conflict, and the containment test uses inclusive bounds so an
 * appointment window ending exactly at the interval end is contained.</p>
 */
public interface StaffAvailabilityRepository extends JpaRepository<StaffAvailability, UUID> {

    /**
     * The availability intervals that fully contain {@code [start, end)},
     * taken under a pessimistic row lock. This is the database-backed
     * serialization grain of the Task 9 conflict defense: two concurrent
     * appointment creates for the same professional's interval block here
     * until the first transaction commits, so the loser's subsequent
     * overlap check runs against the committed state instead of racing it
     * (bounded JPA/H2 defense — never claimed as distributed locking or
     * production concurrency capacity, docs/plan3.md §8.7).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from StaffAvailability a
            where a.branchId = :branchId and a.staffMemberId = :staffMemberId
              and a.startsAt <= :start and a.endsAt >= :end
            order by a.startsAt asc, a.id asc
            """)
    List<StaffAvailability> lockContainingIntervals(@Param("branchId") UUID branchId,
                                                    @Param("staffMemberId") UUID staffMemberId,
                                                    @Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end);

    /**
     * Strict-overlap existence check for a candidate interval — a new
     * availability interval may not overlap an existing one for the same
     * professional in the same branch, while exactly-adjacent intervals
     * are allowed.
     */
    @Query("""
            select count(a) from StaffAvailability a
            where a.branchId = :branchId and a.staffMemberId = :staffMemberId
              and a.startsAt < :end and a.endsAt > :start
            """)
    long countOverlapping(@Param("branchId") UUID branchId,
                          @Param("staffMemberId") UUID staffMemberId,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end);

    /**
     * Deterministic windowed read: the intervals intersecting the half-open
     * {@code [from, to)} window for one professional in one branch, ordered
     * chronologically (then by id as the stable tie-break).
     */
    @Query("""
            select a from StaffAvailability a
            where a.branchId = :branchId and a.staffMemberId = :staffMemberId
              and a.startsAt < :to and a.endsAt > :from
            order by a.startsAt asc, a.id asc
            """)
    List<StaffAvailability> findWindow(@Param("branchId") UUID branchId,
                                       @Param("staffMemberId") UUID staffMemberId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);
}

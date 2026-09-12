package com.mamtrex.hospital.emergency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Emergency-visit repository. The derived status count backs the read-only
 * dashboard's active-visit bucket (docs/plan2.md Task 5).
 *
 * <p>The branch-scoped lookups (docs/plan3.md Task 8) are the only
 * emergency-visit authority path for /api/emergency-visits: a cross-branch
 * id — and a legacy row with null ownership — resolves to empty,
 * indistinguishable from a nonexistent row. Branch-scoped API paths must
 * never fall back to whole-table {@code findAll()}, unscoped
 * {@code findById()}, or {@code existsById()}.</p>
 */
public interface EmergencyVisitRepository extends JpaRepository<EmergencyVisit, UUID> {

    /** Count of visits currently in the given lifecycle status. */
    long countByStatus(String status);

    /** Every visit owned by one branch; legacy null-ownership rows never match. */
    List<EmergencyVisit> findByBranchId(UUID branchId);

    /** Branch-scoped detail lookup: the same 404 for unknown, cross-branch, and legacy ids. */
    Optional<EmergencyVisit> findByIdAndBranchId(UUID id, UUID branchId);

    /** Branch-scoped existence check backing the delete command. */
    boolean existsByIdAndBranchId(UUID id, UUID branchId);

    /*
     * Task 10 dashboard aggregation (docs/plan3.md §4.7): one grouped
     * (branch, status) count restricted to an explicit branch-id set —
     * never a whole-table read. Totals and the active bucket (WAITING +
     * IN_TREATMENT) derive from the same tuples, so any other persisted
     * status still counts honestly toward the total.
     */
    @Query("select e.branchId as branchId, e.status as status, count(e) as total from EmergencyVisit e "
            + "where e.branchId in :branchIds group by e.branchId, e.status")
    List<BranchStatusCount> countByBranchIdInGroupedByStatus(@Param("branchIds") Collection<UUID> branchIds);

    /** One grouped (branch, status) count tuple of the query above. */
    interface BranchStatusCount {
        UUID getBranchId();
        String getStatus();
        long getTotal();
    }
}

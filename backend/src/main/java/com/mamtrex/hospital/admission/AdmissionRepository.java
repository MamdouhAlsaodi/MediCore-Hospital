package com.mamtrex.hospital.admission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admission repository. The derived status count backs the read-only
 * dashboard's open-admissions bucket (docs/plan2.md Task 5).
 *
 * <p>The branch-scoped lookups (docs/plan3.md Task 7A) are the only
 * admission authority path for /api/admissions: a cross-branch id — and a
 * legacy row with null ownership — resolves to empty, indistinguishable
 * from a nonexistent row. Branch-scoped API paths must never fall back to
 * whole-table {@code findAll()}, unscoped {@code findById()}, or
 * {@code existsById()}.</p>
 */
public interface AdmissionRepository extends JpaRepository<Admission, UUID> {

    /** Count of admissions currently in the given lifecycle status. */
    long countByStatus(String status);

    /** Every admission owned by one branch; legacy null-ownership rows never match. */
    List<Admission> findByBranchId(UUID branchId);

    /** Branch-scoped detail lookup: the same 404 for unknown, cross-branch, and legacy ids. */
    Optional<Admission> findByIdAndBranchId(UUID id, UUID branchId);

    /** Branch-scoped existence check backing the delete command. */
    boolean existsByIdAndBranchId(UUID id, UUID branchId);

    /*
     * Task 10 dashboard aggregation (docs/plan3.md §4.7): one grouped
     * (branch, status) count restricted to an explicit branch-id set —
     * never a whole-table read. Totals and the open bucket derive from the
     * same tuples, so a status outside the canonical lifecycle still counts
     * honestly toward the total.
     */
    @Query("select a.branchId as branchId, a.status as status, count(a) as total from Admission a "
            + "where a.branchId in :branchIds group by a.branchId, a.status")
    List<BranchStatusCount> countByBranchIdInGroupedByStatus(@Param("branchIds") Collection<UUID> branchIds);

    /** One grouped (branch, status) count tuple of the query above. */
    interface BranchStatusCount {
        UUID getBranchId();
        String getStatus();
        long getTotal();
    }
}

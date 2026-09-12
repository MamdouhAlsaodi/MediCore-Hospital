package com.mamtrex.hospital.bed;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bed reads for the branch-scoped workflow (docs/plan3.md Task 6): a
 * cross-branch id resolves to empty, indistinguishable from a nonexistent
 * row, and the duplicate pre-check resolves one natural key inside one
 * branch. The (branch_id, ward, room, bedNumber) DB unique constraint on
 * the entity remains the concurrency backstop.
 */
public interface BedRepository extends JpaRepository<Bed, java.util.UUID> {

    Optional<Bed> findByIdAndBranchId(UUID id, UUID branchId);

    List<Bed> findByBranchId(UUID branchId);

    Optional<Bed> findByBranchIdAndWardAndRoomAndBedNumber(UUID branchId, String ward, String room, String bedNumber);

    /*
     * Task 10 dashboard aggregation (docs/plan3.md §4.7): one grouped
     * (branch, occupancyStatus) count restricted to an explicit branch-id
     * set — never a whole-table read. Every row reachable through a branch
     * was created through the normalized lifecycle, so the canonical
     * AVAILABLE/OCCUPIED/MAINTENANCE/OUT_OF_SERVICE buckets are the whole
     * contract; OCCUPIED stays admission-owned (docs/plan3.md Task 7).
     */
    @Query("select b.branch.id as branchId, b.occupancyStatus as status, count(b) as total from Bed b "
            + "where b.branch.id in :branchIds group by b.branch.id, b.occupancyStatus")
    List<BranchStatusCount> countByBranchIdInGroupedByStatus(@Param("branchIds") Collection<UUID> branchIds);

    /** One grouped (branch, status) count tuple of the query above. */
    interface BranchStatusCount {
        java.util.UUID getBranchId();
        String getStatus();
        long getTotal();
    }
}

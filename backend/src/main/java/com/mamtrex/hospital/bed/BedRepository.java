package com.mamtrex.hospital.bed;

import org.springframework.data.jpa.repository.JpaRepository;

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
}

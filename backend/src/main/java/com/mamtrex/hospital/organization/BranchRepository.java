package com.mamtrex.hospital.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow persistence surface for branches (docs/plan3.md Task 2; Phase 5
 * T018): the hospital-scoped duplicate pre-check and read (a branch lives
 * inside one hospital), the organization ancestor filters behind
 * network-level views, the deterministic active-branch views behind the
 * organization/acting contracts, and the deterministic full review list.
 * Ordering lives in the derived queries so every read is stable.
 */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

    /** Hospital-scoped: the duplicate pre-check key is now (hospital, code). */
    Optional<Branch> findByHospitalIdAndCode(UUID hospitalId, String code);

    /** Organization ancestor filter: deterministic active branches of one network. */
    List<Branch> findByOrganizationIdAndActiveTrueOrderByCodeAsc(UUID organizationId);

    /** Hospital ancestor filter: deterministic active branches of one facility. */
    List<Branch> findByHospitalIdAndActiveTrueOrderByCodeAsc(UUID hospitalId);

    /** First active branch of one hospital in deterministic code order. */
    Optional<Branch> findFirstByHospitalIdAndActiveTrueOrderByCodeAsc(UUID hospitalId);

    /** Ancestor-filtered read: the branch must belong to that hospital. */
    Optional<Branch> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /** Ancestor-filtered read: the branch must belong to that organization. */
    Optional<Branch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Branch> findAllByOrderByCodeAscIdAsc();
}

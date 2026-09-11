package com.mamtrex.hospital.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow persistence surface for branches (docs/plan3.md Task 2): the
 * per-organization duplicate pre-check, the deterministic active-branch
 * view behind the organization DTO, and the deterministic full review
 * list. Ordering lives in the derived queries so every read is stable.
 */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

    Optional<Branch> findByOrganizationIdAndCode(UUID organizationId, String code);

    List<Branch> findByOrganizationIdAndActiveTrueOrderByCodeAsc(UUID organizationId);

    List<Branch> findAllByOrderByCodeAscIdAsc();
}

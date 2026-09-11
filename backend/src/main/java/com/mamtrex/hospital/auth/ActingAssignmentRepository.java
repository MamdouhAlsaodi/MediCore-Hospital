package com.mamtrex.hospital.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow persistence surface for acting assignments (docs/plan3.md Task 3):
 * the deterministic enabled-assignment list behind login, the
 * subject-ownership lookup behind context switching and JWT reloads, and the
 * duplicate pre-checks behind bootstrap provisioning (organization and
 * branch scopes; the DB unique constraint backstops the branch-scope case
 * while null-column tuples keep the organization case pre-check-only).
 */
public interface ActingAssignmentRepository extends JpaRepository<ActingAssignment, UUID> {

    List<ActingAssignment> findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(UUID accountId);

    Optional<ActingAssignment> findByIdAndAccountId(UUID id, UUID accountId);

    Optional<ActingAssignment> findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(
            UUID accountId, Role role, AssignmentScope scope);

    Optional<ActingAssignment> findByAccountIdAndRoleAndScopeAndBranchId(
            UUID accountId, Role role, AssignmentScope scope, UUID branchId);
}

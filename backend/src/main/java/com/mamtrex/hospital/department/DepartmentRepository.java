package com.mamtrex.hospital.department; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*; public interface DepartmentRepository extends JpaRepository<Department,UUID>{
    List<Department> findByBranchIsNotNullOrderByCodeAscIdAsc();
    /* Phase 4 (FR-013): the ORGANIZATION-scope read is org-bounded, never whole-table. */
    List<Department> findByBranchOrganizationIdAndBranchIsNotNullOrderByCodeAscIdAsc(UUID organizationId);
    List<Department> findByBranchIdOrderByCodeAsc(UUID branchId);
    Optional<Department> findByBranchIdAndCode(UUID branchId, String code);
}

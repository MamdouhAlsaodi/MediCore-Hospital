package com.mamtrex.hospital.patient; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param; import java.util.*; public interface PatientRepository extends JpaRepository<Patient,UUID>{Optional<Patient> findByMedicalRecordNumber(String mrn); List<Patient> findByFullNameContainingIgnoreCase(String q); /* Branch-scoped workflow reads (docs/plan3.md Task 4): a cross-branch id resolves to empty, indistinguishable from nonexistent. */ Optional<Patient> findByIdAndBranchId(UUID id,UUID branchId); List<Patient> findByBranchId(UUID branchId); List<Patient> findByBranchIdAndFullNameContainingIgnoreCase(UUID branchId,String q);

/*
 * Task 10 dashboard aggregation (docs/plan3.md §4.7): one grouped count
 * restricted to an explicit branch-id set — never a whole-table read. The
 * service passes exactly the branch ids its verified acting context
 * authorizes, so rows outside the scope can never enter a summary.
 */
@Query("select p.branch.id as branchId, count(p) as total from Patient p where p.branch.id in :branchIds group by p.branch.id")
List<BranchMetric> countByBranchIdInGrouped(@Param("branchIds") Collection<UUID> branchIds);

/** One grouped-count tuple of the query above; projection keeps the repository surface typed. */
interface BranchMetric {
UUID getBranchId();
long getTotal();
} }

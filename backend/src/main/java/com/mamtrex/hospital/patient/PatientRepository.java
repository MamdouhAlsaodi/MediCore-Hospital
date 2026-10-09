package com.mamtrex.hospital.patient; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param; import java.util.*; public interface PatientRepository extends JpaRepository<Patient,UUID>{Optional<Patient> findByMedicalRecordNumber(String mrn); List<Patient> findByFullNameContainingIgnoreCase(String q);
/*
 * Phase 5 US3 (T068; specs/005 data-model.md): visibility authority is the
 * ACTIVE hospital access grant, never branch ownership and never patient
 * existence. Every scoped read below joins the grant table through an
 * exists-subquery, and every query also pins the organization so a foreign
 * network can never resolve even with a forged grant row (defense in depth
 * on top of the V6 composite foreign keys). A cross-hospital id resolves
 * to empty, indistinguishable from nonexistent.
 */
String ACTIVE_ACCESS = """
    exists (select a from PatientHospitalAccess a
            where a.patient.id = p.id
              and a.hospital.id = :hospitalId
              and a.status = :active)""";

@Query("select p from Patient p where p.id = :id and p.organization.id = :organizationId and " + ACTIVE_ACCESS)
Optional<Patient> findByIdAndActiveHospitalAccess(@Param("id") UUID id,
                                                  @Param("organizationId") UUID organizationId,
                                                  @Param("hospitalId") UUID hospitalId,
                                                  @Param("active") PatientAccessStatus active);

@Query("select p from Patient p where p.organization.id = :organizationId and " + ACTIVE_ACCESS)
List<Patient> findByActiveHospitalAccess(@Param("organizationId") UUID organizationId,
                                         @Param("hospitalId") UUID hospitalId,
                                         @Param("active") PatientAccessStatus active);

@Query("select p from Patient p where p.organization.id = :organizationId "
        + "and lower(p.fullName) like lower(concat('%', :q, '%')) and " + ACTIVE_ACCESS)
List<Patient> searchByActiveHospitalAccess(@Param("organizationId") UUID organizationId,
                                           @Param("hospitalId") UUID hospitalId,
                                           @Param("q") String q,
                                           @Param("active") PatientAccessStatus active);

/*
 * Task 10 dashboard aggregation (docs/plan3.md §4.7): one grouped count
 * restricted to an explicit branch-id set — never a whole-table read. The
 * service passes exactly the branch ids its verified acting context
 * authorizes, so rows outside the scope can never enter a summary. The
 * legacy branch column stays as the provenance/migration seam this
 * aggregation reads.
 */
@Query("select p.branch.id as branchId, count(p) as total from Patient p where p.branch.id in :branchIds group by p.branch.id")
List<BranchMetric> countByBranchIdInGrouped(@Param("branchIds") Collection<UUID> branchIds);

/** One grouped-count tuple of the query above; projection keeps the repository surface typed. */
interface BranchMetric {
UUID getBranchId();
long getTotal();
} }

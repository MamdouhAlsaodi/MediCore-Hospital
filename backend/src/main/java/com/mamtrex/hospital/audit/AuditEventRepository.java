package com.mamtrex.hospital.audit; import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;

/**
 * Audit evidence queries (docs/plan3.md Task 11; Phase 5 US7 T130). Each
 * finder answers one scope slice of the filtered read: the organization's
 * attributed rows, one hospital's rows (the US7 hospital-wide slice, served
 * by the {@code idx_audit_events_hospital_occurred} index), one branch's
 * rows, one department's rows, and the context-less legacy rows. The
 * controller composes exactly one scope slice with the request filters,
 * so no read can escape its acting scope, and legacy rows are reachable
 * only through the explicit legacy finder.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent,UUID>{

 List<AuditEvent> findByOrganizationIdOrderByOccurredAtDesc(UUID organizationId);

 /** One hospital facility's attributed rows: the hospital-wide US7 slice (scoped, never widened). */
 List<AuditEvent> findByHospitalIdOrderByOccurredAtDesc(UUID hospitalId);

 List<AuditEvent> findByBranchIdOrderByOccurredAtDesc(UUID branchId);

 List<AuditEvent> findByDepartmentIdOrderByOccurredAtDesc(UUID departmentId);

 /** Truly context-less rows: no assignment or organization/branch/department attribution. */
 List<AuditEvent> findByAssignmentIdIsNullAndOrganizationIdIsNullAndBranchIdIsNullAndDepartmentIdIsNullOrderByOccurredAtDesc();
}

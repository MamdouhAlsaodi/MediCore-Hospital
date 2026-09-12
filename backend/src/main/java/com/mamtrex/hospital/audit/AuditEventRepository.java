package com.mamtrex.hospital.audit; import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;

/**
 * Audit evidence queries (docs/plan3.md Task 11). Each finder answers one
 * scope slice of the filtered read: the organization's attributed rows, one
 * branch's rows, one department's rows, and the context-less legacy rows.
 * The controller composes exactly one scope slice with the request filters,
 * so no read can escape its acting scope, and legacy rows are reachable
 * only through the explicit legacy finder.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent,UUID>{

 List<AuditEvent> findByOrganizationIdOrderByOccurredAtDesc(UUID organizationId);

 List<AuditEvent> findByBranchIdOrderByOccurredAtDesc(UUID branchId);

 List<AuditEvent> findByDepartmentIdOrderByOccurredAtDesc(UUID departmentId);

 /** Context-less rows (no acting assignment): legacy/unassigned, never guessed into any scope. */
 List<AuditEvent> findByAssignmentIdIsNullOrderByOccurredAtDesc();
}

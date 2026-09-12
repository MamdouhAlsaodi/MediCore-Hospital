package com.mamtrex.hospital.audit;

import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable audit evidence row (docs/plan3.md Task 11). Beyond the
 * original evidence fields (actor, action, resource, safe concise details,
 * timestamp), every success event carries the full acting context — the
 * assignment pointer, role, scope, organization, selected branch, and
 * optional department it was performed under — plus the one bounded
 * correlation id validated at the request boundary, so a command is
 * attributable to identity, acting assignment, and place in one row.
 *
 * <p>Context columns are nullable by design: rows recorded without an
 * authenticated acting context (startup/bootstrap recording) keep them null
 * and are surfaced only as {@code legacy/unassigned} to organization-scoped
 * ADMIN — their ownership is never guessed. No bearer token, password,
 * request body, stack trace, or sensitive personal field ever belongs in a
 * row: {@code details} stays the short safe mutation label supplied by the
 * recording service, and {@code correlationId} is bounded by the filter's
 * validated shape.</p>
 */
@Entity @Table(name="audit_events") public class AuditEvent extends BaseEntity { @Column(nullable=false) private String actor; @Column(nullable=false) private String action; private String resourceType; private String resourceId; @Column(length=2000) private String details; private Instant occurredAt=Instant.now();
 @Column(length=64) private String correlationId;
 private UUID assignmentId; @Enumerated(EnumType.STRING) private Role role; @Enumerated(EnumType.STRING) private AssignmentScope scope; private UUID organizationId; private UUID branchId; private UUID departmentId;
 protected AuditEvent(){}
 /** The one recording constructor: evidence fields plus the acting context and the validated correlation id (both nullable for legacy/unassigned rows). */
 public AuditEvent(String actor,String action,String resourceType,String resourceId,String details,ActingContext context,String correlationId){
  this.actor=actor;this.action=action;this.resourceType=resourceType;this.resourceId=resourceId;this.details=details;this.correlationId=correlationId;
  if(context!=null){this.assignmentId=context.assignmentId();this.role=context.role();this.scope=context.scope();
   this.organizationId=context.organizationId();this.branchId=context.branchId();this.departmentId=context.departmentId();}}
 public String getActor(){return actor;}
 public String getAction(){return action;}
 public String getResourceType(){return resourceType;}
 public String getResourceId(){return resourceId;}
 public String getDetails(){return details;}
 public Instant getOccurredAt(){return occurredAt;}
 public String getCorrelationId(){return correlationId;}
 public UUID getAssignmentId(){return assignmentId;}
 public Role getRole(){return role;}
 public AssignmentScope getScope(){return scope;}
 public UUID getOrganizationId(){return organizationId;}
 public UUID getBranchId(){return branchId;}
 public UUID getDepartmentId(){return departmentId;}
}

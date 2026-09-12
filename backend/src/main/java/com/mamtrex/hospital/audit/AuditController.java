package com.mamtrex.hospital.audit;

import com.mamtrex.hospital.auth.ActingContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * The scope-aware, DTO-only audit read (docs/plan3.md Task 11). The route
 * stays ADMIN-only in SecurityConfig; within ADMIN the acting context decides
 * the visible slice server-side — an ORGANIZATION-scoped ADMIN inspects the
 * whole organization plus context-less rows marked {@code legacy/unassigned}
 * (ownership is never guessed), a BRANCH-scoped ADMIN only its own branch's
 * rows, and a DEPARTMENT-scoped ADMIN only its department's rows — so no
 * client filter can ever widen a view.
 *
 * <p>The four request filters (branch, resource type, actor, correlation id)
 * apply conjunctively inside the scope slice: for scoped ADMINs a foreign
 * branch filter therefore answers the empty set, never a widened list, and a
 * malformed branch id fails typed deserialization as the shared 400. The
 * response is a strict allowlist DTO — the evidence fields, the acting
 * context, the bounded correlation id, and the legacy mark — never the JPA
 * entity and none of its persistence metadata. Reads record no audit
 * events.</p>
 */
@RestController @RequestMapping("/api/audit") public class AuditController {
 private static final String LEGACY_UNASSIGNED = "legacy/unassigned";
 private final AuditEventRepository repo;
 public AuditController(AuditEventRepository r){repo=r;}

 /** Strict allowlist of one audit evidence row; never the entity. */
 public record AuditEventView(UUID id,String actor,String action,String resourceType,String resourceId,
  String details,Instant occurredAt,UUID assignmentId,String role,String scope,
  UUID organizationId,UUID branchId,UUID departmentId,String correlationId,String branchAttribution){}

 @GetMapping public List<AuditEventView> list(@RequestParam(required=false) UUID branchId,
  @RequestParam(required=false) String resourceType,@RequestParam(required=false) String actor,
  @RequestParam(required=false) String correlationId){
  ActingContext context=currentContext();
  Stream<AuditEvent> scoped=switch(context.scope()){
   case ORGANIZATION -> Stream.concat(
     repo.findByOrganizationIdOrderByOccurredAtDesc(context.organizationId()).stream(),
     repo.findByAssignmentIdIsNullOrderByOccurredAtDesc().stream())
    .sorted(Comparator.comparing(AuditEvent::getOccurredAt).reversed());
   case BRANCH -> repo.findByBranchIdOrderByOccurredAtDesc(context.branchId()).stream();
   case DEPARTMENT -> repo.findByDepartmentIdOrderByOccurredAtDesc(context.departmentId()).stream();
  };
  return scoped
   .filter(e->branchId==null||branchId.equals(e.getBranchId()))
   .filter(e->matches(resourceType,e.getResourceType()))
   .filter(e->matches(actor,e.getActor()))
   .filter(e->matches(correlationId,e.getCorrelationId()))
   .map(AuditController::toView)
   .toList();
 }

 /** Blank query values are treated as absent; no filter is ever partially applied. */
 private static boolean matches(String filter,String value){
  return filter==null||filter.isBlank()||(value!=null&&value.equals(filter));
 }

 private static AuditEventView toView(AuditEvent e){
  return new AuditEventView(e.getId(),e.getActor(),e.getAction(),e.getResourceType(),e.getResourceId(),
   e.getDetails(),e.getOccurredAt(),e.getAssignmentId(),
   e.getRole()==null?null:e.getRole().name(),e.getScope()==null?null:e.getScope().name(),
   e.getOrganizationId(),e.getBranchId(),e.getDepartmentId(),e.getCorrelationId(),
   e.getAssignmentId()==null?LEGACY_UNASSIGNED:null);
 }

 private static ActingContext currentContext(){
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  if(authentication!=null&&authentication.getPrincipal() instanceof ActingContext context){return context;}
  throw new AccessDeniedException("No acting context is available");
 }
}

package com.mamtrex.hospital.audit;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import com.mamtrex.hospital.auth.ActingContext;

/**
 * The single audit recording seam (docs/plan3.md Task 11): every success
 * event attributes the mutation to the authenticated actor, the acting
 * context re-derived by the JWT filter (assignment id, role, scope,
 * organization, selected branch, optional department), and the bounded
 * correlation id validated by {@link CorrelationIdFilter} at the request
 * boundary. Requests without an authenticated acting context (startup
 * recording) keep every context column null — those rows surface only as
 * {@code legacy/unassigned} to organization-scoped ADMIN and are never
 * guessed into a branch.
 *
 * <p>Callers pass only the safe concise mutation label as {@code details};
 * the service never inspects bodies, tokens, credentials, or stack traces,
 * so none of that material can reach storage. Failure paths never reach
 * this service because recording happens inside the successful command's
 * transaction — every declared failure class (400/401/403/404/409) leaves
 * no event.</p>
 */
@Service public class AuditService { private final AuditEventRepository repo; public AuditService(AuditEventRepository r){repo=r;}
 public void record(String action,String type,String id,String details){
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  ActingContext context=authentication!=null&&authentication.getPrincipal() instanceof ActingContext c?c:null;
  String actor=authentication==null?"system":authentication.getName();
  repo.save(new AuditEvent(actor,action,type,id,details,context,CorrelationIdFilter.current()));}
}

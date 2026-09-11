package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.organization.Branch;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the acting-context lifecycle (docs/plan3.md Task 3): credential
 * verification, deterministic assignment selection at login, subject-owned
 * context switching, and the per-request reload the JWT filter depends on.
 *
 * <p>Identity and assignment stay separate: {@link UserAccount} only proves
 * the credentials, while every scrap of authority comes from an enabled
 * {@link ActingAssignment} owned by that account and re-checked against
 * current organization/branch/department state. There is deliberately no
 * legacy-role fallback — an account without a valid enabled assignment and
 * an eligible active branch cannot log in, and the refusal is the same
 * non-enumerating 401 as a wrong password. The JWT carries only the
 * assignment pointer plus display claims; {@link JwtFilter} reloads the
 * server state through {@link #reload}, so deleted/disabled assignments or
 * branches invalidate outstanding tokens immediately.</p>
 *
 * <p>The response records are strict allowlists — IDs, labels, one role, one
 * scope, and the enabled flag — and never JPA entities or persistence
 * metadata. A successful context switch records exactly one safe audit event
 * (no token, password, request body, or role union); failed switches record
 * nothing because validation precedes the audit write inside one
 * transaction.</p>
 */
@Service
public class ActingContextService {

    private final UserAccountRepository accounts;
    private final ActingAssignmentRepository assignments;
    private final BranchAccessService branchAccess;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final AuditService auditService;

    public ActingContextService(UserAccountRepository accounts,
                                ActingAssignmentRepository assignments,
                                BranchAccessService branchAccess,
                                JwtService jwt,
                                PasswordEncoder encoder,
                                AuditService auditService) {
        this.accounts = accounts;
        this.assignments = assignments;
        this.branchAccess = branchAccess;
        this.jwt = jwt;
        this.encoder = encoder;
        this.auditService = auditService;
    }

    /** Strict allowlist of one acting assignment in the login/switch response. */
    public record AssignmentView(UUID id, String role, String scope,
                                 UUID organizationId, String organizationLabel,
                                 UUID branchId, String branchLabel,
                                 UUID departmentId, String departmentLabel,
                                 boolean enabled) {
    }

    /** Strict allowlist of the selected acting context in the response. */
    public record ActingContextView(String username, UUID assignmentId, String role, String scope,
                                    UUID organizationId, UUID branchId, UUID departmentId) {
    }

    /** Strict allowlist of the whole login/switch response; assignments lists only currently valid enabled rows. */
    public record Session(String accessToken, String tokenType, String username, List<String> roles,
                          List<AssignmentView> assignments, ActingContextView actingContext) {
    }

    private record Resolved(ActingAssignment assignment, ActingContext context) {
    }

    /**
     * Verifies the credentials and selects the first valid enabled assignment
     * in deterministic (role, scope, id) order. Only assignments that pass
     * the same current-state invariants used for authorization are listed in
     * the session view; an enabled but inconsistent row is omitted rather
     * than rendered. Unknown username, disabled account, wrong password, and
     * "no valid assignment/branch" are all the same non-enumerating
     * {@link IllegalArgumentException}, mapped to the established 401 by the
     * controller.
     */
    @Transactional
    public Session login(String username, String password) {
        UserAccount account = accounts.findByUsername(username)
                .filter(UserAccount::isEnabled)
                .filter(candidate -> encoder.matches(password, candidate.getPasswordHash()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        List<Resolved> valid = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).stream()
                .map(this::tryResolve)
                .flatMap(Optional::stream)
                .toList();
        Resolved selected = valid.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        return session(jwt.issue(selected.context()), account, selected, assignmentsOf(valid));
    }

    /**
     * Issues a replacement token for an assignment owned by the
     * authenticated subject. Foreign, unknown, disabled, and inconsistent
     * targets all fail closed with the same {@link BranchAccessService}
     * refusal (403), so no other user's assignment existence leaks. The old
     * token stays cryptographically valid until expiry, but every request
     * reload re-checks its bound assignment, so disabling or deleting it
     * invalidates the old token immediately.
     */
    @Transactional
    public Session switchContext(ActingContext current, UUID targetAssignmentId, UUID requestedBranchId) {
        UserAccount account = accounts.findByUsername(current.username())
                .filter(UserAccount::isEnabled)
                .orElseThrow(BranchAccessService::refused);
        ActingAssignment target = assignments.findByIdAndAccountId(targetAssignmentId, account.getId())
                .filter(ActingAssignment::isEnabled)
                .orElseThrow(BranchAccessService::refused);
        ActingContext context = resolveContext(target, requestedBranchId);
        String token = jwt.issue(context);
        auditService.record("SWITCH", "ActingAssignment", target.getId().toString(),
                "acting " + target.getRole() + " (" + target.getScope() + ")");
        List<Resolved> valid = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).stream()
                .map(this::tryResolve)
                .flatMap(Optional::stream)
                .toList();
        return session(token, account, new Resolved(target, context), assignmentsOf(valid));
    }

    /**
     * The JWT filter boundary: rebuilds the acting context purely from
     * server state for the token's structural claims, then requires every
     * structural field of the rebuilt context to match the claims exactly —
     * assignment pointer, scope, organization, selected branch, and
     * department (null-safe). Any missing/disabled account or assignment,
     * any inconsistent assignment, any missing/inactive selected branch, and
     * any stale or tampered structural claim yields empty — the request
     * stays unauthenticated. The role claim is not part of the pointer and
     * is never read here; the authority is always re-derived from the
     * assignment row.
     */
    public Optional<ActingContext> reload(JwtService.StructuralClaims claims) {
        return accounts.findByUsername(claims.subject())
                .filter(UserAccount::isEnabled)
                .flatMap(account -> assignments.findByIdAndAccountId(claims.assignmentId(), account.getId())
                        .filter(ActingAssignment::isEnabled))
                .flatMap(assignment -> tryResolve(assignment, claims.branchId()))
                .filter(resolved -> matchesStructuralClaims(resolved.context(), claims))
                .map(Resolved::context);
    }

    /** Every structural field must match, including department null semantics; the role claim is never compared. */
    private static boolean matchesStructuralClaims(ActingContext context, JwtService.StructuralClaims claims) {
        return context.assignmentId().equals(claims.assignmentId())
                && context.scope() == claims.scope()
                && context.organizationId().equals(claims.organizationId())
                && context.branchId().equals(claims.branchId())
                && Objects.equals(context.departmentId(), claims.departmentId());
    }

    /** The resolved assignments behind a session view, in the repository's deterministic order. */
    private static List<ActingAssignment> assignmentsOf(List<Resolved> resolved) {
        return resolved.stream().map(Resolved::assignment).toList();
    }

    private Optional<Resolved> tryResolve(ActingAssignment assignment) {
        return tryResolve(assignment, null);
    }

    private Optional<Resolved> tryResolve(ActingAssignment assignment, UUID requestedBranchId) {
        try {
            return Optional.of(new Resolved(assignment, resolveContext(assignment, requestedBranchId)));
        } catch (BranchAccessService.RefusedException refused) {
            return Optional.empty();
        }
    }

    /**
     * Applies the scope rules to one assignment: ORGANIZATION selects the
     * deterministic first active branch (login) or the requested active
     * branch inside the organization (switch); BRANCH acts on its fixed
     * active branch; DEPARTMENT derives its branch from the department.
     * Cross-scope invariants (a branch on an ORGANIZATION assignment, a
     * department on a BRANCH assignment, a missing department) fail closed.
     */
    private ActingContext resolveContext(ActingAssignment assignment, UUID requestedBranchId) {
        UUID organizationId = assignment.getOrganization().getId();
        branchAccess.requireOrganization(organizationId);
        Branch selected = switch (assignment.getScope()) {
            case ORGANIZATION -> {
                if (assignment.getDepartment() != null) {
                    throw BranchAccessService.refused();
                }
                yield requestedBranchId == null
                        ? branchAccess.deterministicActiveBranch(organizationId)
                                .orElseThrow(BranchAccessService::refused)
                        : branchAccess.requireActiveBranchInOrganization(organizationId, requestedBranchId);
            }
            case BRANCH -> {
                if (assignment.getDepartment() != null) {
                    throw BranchAccessService.refused();
                }
                yield branchAccess.requireFixedAssignmentBranch(assignment, organizationId, requestedBranchId);
            }
            case DEPARTMENT ->
                    branchAccess.requireDepartmentBranch(assignment, organizationId, requestedBranchId);
        };
        return new ActingContext(assignment.getAccount().getUsername(), assignment.getId(),
                assignment.getRole(), assignment.getScope(), organizationId, selected.getId(),
                assignment.getScope() == AssignmentScope.DEPARTMENT ? assignment.getDepartment().getId() : null);
    }

    private Session session(String token, UserAccount account, Resolved selected, List<ActingAssignment> enabled) {
        return new Session(token, "Bearer", account.getUsername(),
                List.of(selected.assignment().getRole().name()),
                enabled.stream().map(this::toView).toList(),
                toView(selected.context()));
    }

    private AssignmentView toView(ActingAssignment assignment) {
        UUID branchId = assignment.getBranch() == null ? null : assignment.getBranch().getId();
        UUID departmentId = assignment.getDepartment() == null ? null : assignment.getDepartment().getId();
        return new AssignmentView(assignment.getId(), assignment.getRole().name(), assignment.getScope().name(),
                assignment.getOrganization().getId(),
                branchAccess.organizationLabel(assignment.getOrganization().getId()),
                branchId, branchId == null ? null : branchAccess.branchLabel(branchId),
                departmentId, departmentId == null ? null : branchAccess.departmentLabel(departmentId),
                assignment.isEnabled());
    }

    private static ActingContextView toView(ActingContext context) {
        return new ActingContextView(context.username(), context.assignmentId(),
                context.role().name(), context.scope().name(),
                context.organizationId(), context.branchId(), context.departmentId());
    }
}

package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.HospitalFacility;
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
                                 UUID hospitalId, String hospitalLabel,
                                 UUID branchId, String branchLabel,
                                 UUID departmentId, String departmentLabel,
                                 boolean enabled) {
    }

    /** Strict allowlist of the selected acting context in the response. */
    public record ActingContextView(String username, UUID assignmentId, String role, String scope,
                                    UUID organizationId, UUID hospitalId, UUID branchId, UUID departmentId) {
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
     *
     * <p>Phase 5 (T050): the requested hospital/branch ids only ever select
     * among the server-issued targets of the target assignment. Where the
     * shape demands a selection, the target is mandatory: an ORGANIZATION
     * switch must carry both the target hospital and the target branch, and
     * a HOSPITAL switch must carry the target branch inside its fixed
     * facility. Fixed scopes (BRANCH, DEPARTMENT) derive their chain from
     * the assignment and accept only ids that match it. Every supplied id is
     * revalidated against the full active ancestor chain before any context
     * is issued.</p>
     */
    @Transactional
    public Session switchContext(ActingContext current, UUID targetAssignmentId,
                                 UUID requestedHospitalId, UUID requestedBranchId) {
        UserAccount account = accounts.findByUsername(current.username())
                .filter(UserAccount::isEnabled)
                .orElseThrow(BranchAccessService::refused);
        ActingAssignment target = assignments.findByIdAndAccountId(targetAssignmentId, account.getId())
                .filter(ActingAssignment::isEnabled)
                .orElseThrow(BranchAccessService::refused);
        // Where the shape demands a selection target, the switch must carry
        // it: both selection scopes must name their target branch. The
        // hospital is either supplied and matched against the server chain
        // or derived from it — never taken on faith (FR-007, T050).
        if ((target.getScope() == AssignmentScope.ORGANIZATION
                || target.getScope() == AssignmentScope.HOSPITAL) && requestedBranchId == null) {
            throw BranchAccessService.refused();
        }
        ActingContext context = resolveContext(target, requestedHospitalId, requestedBranchId);
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
                .flatMap(assignment -> tryResolve(assignment, claims.hospitalId(), claims.branchId()))
                .filter(resolved -> matchesStructuralClaims(resolved.context(), claims))
                .map(Resolved::context);
    }

    /**
     * Every structural field must match, including hospital presence and
     * department null semantics (T052); the role claim is never compared.
     * A token whose hospital/branch pair does not match the server-derived
     * chain stays unauthenticated even when both ids exist.
     */
    private static boolean matchesStructuralClaims(ActingContext context, JwtService.StructuralClaims claims) {
        return context.assignmentId().equals(claims.assignmentId())
                && context.scope() == claims.scope()
                && context.organizationId().equals(claims.organizationId())
                && context.hospitalId().equals(claims.hospitalId())
                && context.branchId().equals(claims.branchId())
                && Objects.equals(context.departmentId(), claims.departmentId());
    }

    /** The resolved assignments behind a session view, in the repository's deterministic order. */
    private static List<ActingAssignment> assignmentsOf(List<Resolved> resolved) {
        return resolved.stream().map(Resolved::assignment).toList();
    }

    private Optional<Resolved> tryResolve(ActingAssignment assignment) {
        return tryResolve(assignment, null, null);
    }

    private Optional<Resolved> tryResolve(ActingAssignment assignment, UUID requestedHospitalId,
                                          UUID requestedBranchId) {
        try {
            return Optional.of(new Resolved(assignment, resolveContext(assignment, requestedHospitalId,
                    requestedBranchId)));
        } catch (BranchAccessService.RefusedException refused) {
            return Optional.empty();
        }
    }

    /**
     * Applies the scope rules to one assignment against the full server-
     * owned hierarchy (Phase 5 T048-T050). The requested hospital/branch
     * pair arrives from the switch body, or — on the per-request reload —
     * from the token's structural claims, so all three entry points enforce
     * the identical chain:
     * <ul>
     *   <li>ORGANIZATION selects the deterministic first usable branch of
     *       the network (login/reload defaults) or exactly the requested
     *       active branch of the organization (switch); the branch's own
     *       facility becomes the acting hospital, and a supplied hospital
     *       id must match it (FR-007: client ids only select among
     *       server-issued targets, never widen).</li>
     *   <li>HOSPITAL selects inside its fixed facility (which must be
     *       active and belong to the assignment's organization); a
     *       requested hospital id other than the fixed facility is
     *       refused.</li>
     *   <li>BRANCH acts on its fixed active branch; its hospital derives
     *       from the branch and any requested pair must match it.</li>
     *   <li>DEPARTMENT derives its chain from the department under the
     *       same match rule.</li>
     * </ul>
     * Cross-scope invariants (a branch on an ORGANIZATION assignment, a
     * department on a BRANCH or HOSPITAL assignment, a missing department)
     * fail closed. The acting hospital of the returned context is always
     * the selected branch's own facility — never a client-supplied id.
     */
    private ActingContext resolveContext(ActingAssignment assignment, UUID requestedHospitalId,
                                         UUID requestedBranchId) {
        UUID organizationId = assignment.getOrganization().getId();
        branchAccess.requireOrganization(organizationId);
        Branch selected = switch (assignment.getScope()) {
            case ORGANIZATION -> {
                if (assignment.getDepartment() != null || assignment.getHospital() != null) {
                    throw BranchAccessService.refused();
                }
                if (requestedBranchId == null) {
                    if (requestedHospitalId != null) {
                        // a hospital id alone can never select a branch
                        throw BranchAccessService.refused();
                    }
                    yield branchAccess.deterministicActiveBranch(organizationId)
                            .orElseThrow(BranchAccessService::refused);
                }
                Branch selectedBranch =
                        branchAccess.requireActiveBranchInOrganization(organizationId, requestedBranchId);
                branchAccess.requireSameHospital(selectedBranch.getHospital().getId(), requestedHospitalId);
                yield selectedBranch;
            }
            case HOSPITAL -> {
                if (assignment.getDepartment() != null || assignment.getBranch() != null) {
                    throw BranchAccessService.refused();
                }
                HospitalFacility hospital = assignment.getHospital();
                if (hospital == null) {
                    throw BranchAccessService.refused();
                }
                branchAccess.requireSameHospital(hospital.getId(), requestedHospitalId);
                branchAccess.requireActiveHospitalInOrganization(organizationId, hospital.getId());
                yield requestedBranchId == null
                        ? branchAccess.deterministicActiveBranchInHospital(hospital.getId())
                                .orElseThrow(BranchAccessService::refused)
                        : branchAccess.requireActiveBranchInHospital(hospital.getId(), requestedBranchId);
            }
            case BRANCH -> {
                if (assignment.getDepartment() != null) {
                    throw BranchAccessService.refused();
                }
                yield branchAccess.requireFixedAssignmentBranch(assignment, organizationId,
                        requestedHospitalId, requestedBranchId);
            }
            case DEPARTMENT ->
                    branchAccess.requireDepartmentBranch(assignment, organizationId,
                            requestedHospitalId, requestedBranchId);
        };
        return new ActingContext(assignment.getAccount().getUsername(), assignment.getId(),
                assignment.getRole(), assignment.getScope(), organizationId,
                selected.getHospital().getId(), selected.getId(),
                assignment.getScope() == AssignmentScope.DEPARTMENT ? assignment.getDepartment().getId() : null);
    }

    private Session session(String token, UserAccount account, Resolved selected, List<ActingAssignment> enabled) {
        return new Session(token, "Bearer", account.getUsername(),
                List.of(selected.assignment().getRole().name()),
                enabled.stream().map(this::toView).toList(),
                toView(selected.context()));
    }

    private AssignmentView toView(ActingAssignment assignment) {
        UUID hospitalId = assignment.getHospital() == null ? null : assignment.getHospital().getId();
        UUID branchId = assignment.getBranch() == null ? null : assignment.getBranch().getId();
        UUID departmentId = assignment.getDepartment() == null ? null : assignment.getDepartment().getId();
        return new AssignmentView(assignment.getId(), assignment.getRole().name(), assignment.getScope().name(),
                assignment.getOrganization().getId(),
                branchAccess.organizationLabel(assignment.getOrganization().getId()),
                hospitalId, hospitalId == null ? null : branchAccess.hospitalLabel(hospitalId),
                branchId, branchId == null ? null : branchAccess.branchLabel(branchId),
                departmentId, departmentId == null ? null : branchAccess.departmentLabel(departmentId),
                assignment.isEnabled());
    }

    private static ActingContextView toView(ActingContext context) {
        return new ActingContextView(context.username(), context.assignmentId(),
                context.role().name(), context.scope().name(),
                context.organizationId(), context.hospitalId(), context.branchId(), context.departmentId());
    }
}

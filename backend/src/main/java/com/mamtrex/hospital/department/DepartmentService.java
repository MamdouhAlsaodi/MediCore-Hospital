package com.mamtrex.hospital.department;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Branch-owned department rules (docs/plan3.md Task 2; acting-context scope
 * added by Phase 4 Task 5, FR-013). Creation resolves the branch reference
 * through the branch repository (unknown is the shared 404), refuses an
 * inactive branch with the controlled conflict, trims every accepted value,
 * and refuses a duplicate code inside one branch twice: the cause-free
 * duplicate pre-check maps to the safe 409 and the {@code (branch_id, code)}
 * DB unique constraint is the concurrency backstop whose translated
 * violation answers the generic conflict. The same code in two branches is
 * two legitimate rows. Every real create/delete owns exactly one audit
 * event; refused writes record nothing.
 *
 * <p>Phase 4 scope rule (FR-013): every read and command derives its reach
 * from the verified acting assignment — never from a query parameter, body
 * value, or header. ORGANIZATION-scope actors keep the organization-wide
 * hierarchy view; BRANCH- and DEPARTMENT-scope actors see and touch only
 * their acting branch's assigned rows, with the shared safe 404 for
 * anything else. A BRANCH/DEPARTMENT-scope create is owned by the acting
 * branch (a body branchId is never authority); only ORGANIZATION-scope
 * hierarchy writes may target a branch explicitly, and only inside their
 * own organization. Rows with no branch are the deliberate legacy seam:
 * they are never disclosed or mutated here.</p>
 */
@Service
@Transactional
public class DepartmentService {

    private final DepartmentRepository departments;
    private final BranchRepository branches;
    private final AuditService audit;

    public DepartmentService(DepartmentRepository departments,
                             BranchRepository branches,
                             AuditService audit) {
        this.departments = departments;
        this.branches = branches;
        this.audit = audit;
    }

    public DepartmentDtos.DepartmentResponse create(DepartmentDtos.CreateDepartmentRequest request) {
        Branch branch = resolveTargetBranch(request.branchId());
        if (!branch.isActive()) {
            throw new InvalidStateTransitionException("Branch " + branch.getCode()
                    + " is not active: departments require an active branch");
        }
        String code = request.code().trim();
        departments.findByBranchIdAndCode(branch.getId(), code).ifPresent(existing -> {
            throw new DuplicateKeyException("Department code already exists in this branch");
        });
        Department saved = departments.save(new Department(branch, code,
                request.name().trim(), request.specialty().trim(), request.location().trim()));
        audit.record("CREATE", "Department", saved.getId().toString(), "created");
        return DepartmentDtos.DepartmentResponse.from(saved);
    }

    /**
     * Acting-context scoped list (FR-013): ORGANIZATION scope sees every
     * assigned row of its organization; BRANCH/DEPARTMENT scope sees only
     * the acting branch's rows. Legacy null-branch rows never match.
     */
    @Transactional(readOnly = true)
    public List<DepartmentDtos.DepartmentResponse> list() {
        ActingContext context = currentContext();
        if (context.scope() == AssignmentScope.ORGANIZATION) {
            return departments
                    .findByBranchOrganizationIdAndBranchIsNotNullOrderByCodeAscIdAsc(context.organizationId())
                    .stream()
                    .map(DepartmentDtos.DepartmentResponse::from)
                    .toList();
        }
        return departments.findByBranchIdOrderByCodeAsc(context.branchId()).stream()
                .map(DepartmentDtos.DepartmentResponse::from)
                .toList();
    }

    /** Acting-context scoped detail (FR-013): the generic 404 for anything outside the scope. */
    @Transactional(readOnly = true)
    public DepartmentDtos.DepartmentResponse get(UUID id) {
        return departments.findById(id)
                .filter(this::isVisibleToActingScope)
                .map(DepartmentDtos.DepartmentResponse::from)
                .orElseThrow(() -> new NotFoundException("Department not found: " + id));
    }

    /** Acting-context scoped delete (FR-013): 404-safe, zero mutations outside the scope. */
    public void delete(UUID id) {
        Department department = departments.findById(id)
                .filter(this::isVisibleToActingScope)
                .orElseThrow(() -> new NotFoundException("Department not found: " + id));
        departments.delete(department);
        audit.record("DELETE", "Department", id.toString(), "deleted");
    }

    /**
     * The create target is server-derived: BRANCH/DEPARTMENT scope acts on
     * its own acting branch (a body branchId is ignored, never authority);
     * ORGANIZATION-scope hierarchy writes may target an explicit branch,
     * and only one inside their own organization.
     */
    private Branch resolveTargetBranch(UUID requestedBranchId) {
        ActingContext context = currentContext();
        if (context.scope() != AssignmentScope.ORGANIZATION) {
            return branches.findById(context.branchId())
                    .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
        }
        Branch branch = branches.findById(requestedBranchId)
                .orElseThrow(() -> new NotFoundException("Branch not found: " + requestedBranchId));
        if (!branch.getOrganization().getId().equals(context.organizationId())) {
            throw new NotFoundException("Branch not found: " + requestedBranchId);
        }
        return branch;
    }

    /** Visibility: assigned rows inside the acting scope; legacy rows never. */
    private boolean isVisibleToActingScope(Department department) {
        Branch branch = department.getBranch();
        if (branch == null) {
            return false;
        }
        ActingContext context = currentContext();
        if (context.scope() == AssignmentScope.ORGANIZATION) {
            return branch.getOrganization().getId().equals(context.organizationId());
        }
        return branch.getId().equals(context.branchId());
    }

    /**
     * Fail-closed seam: the JWT filter guarantees an {@link ActingContext}
     * principal and an existing active selected branch for every authorized
     * request; anything else is refused, never guessed.
     */
    private static ActingContext currentContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActingContext context) {
            return context;
        }
        throw new AccessDeniedException("No acting context is available");
    }
}

package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The narrow reusable server-side branch authorization seam (docs/plan3.md
 * Task 3). Every branch decision — login's deterministic default branch, the
 * context switch's target validation, and the per-request reload — flows
 * through here, so successor tasks can reuse exactly one server-verified
 * branch contract. It validates actual UUID scope/context only: human
 * branch codes and any client-sent role claim are never authorization
 * evidence.
 *
 * <p>All refusals share one exception with one controlled message, so a
 * foreign branch, an inactive branch, an unknown branch, and a broken
 * assignment relationship are indistinguishable to the client and leak no
 * existence information. No organization or department service is involved:
 * the seam reads the hierarchy repositories directly, which keeps the auth
 * package free of service-level cycles.</p>
 */
@Service
public class BranchAccessService {

    private final HospitalOrganizationRepository organizations;
    private final BranchRepository branches;
    private final DepartmentRepository departments;

    public BranchAccessService(HospitalOrganizationRepository organizations,
                               BranchRepository branches,
                               DepartmentRepository departments) {
        this.organizations = organizations;
        this.branches = branches;
        this.departments = departments;
    }

    /** Fail-closed refusal with the single controlled, non-enumerating message. */
    public static final class RefusedException extends RuntimeException {
        RefusedException(String message) {
            super(message);
        }
    }

    static RefusedException refused() {
        return new RefusedException("The selected assignment or branch is not available.");
    }

    /** The organization row must exist for an acting context to be usable. */
    HospitalOrganization requireOrganization(UUID organizationId) {
        return organizations.findById(organizationId).orElseThrow(BranchAccessService::refused);
    }

    /** First active branch of the organization in deterministic code order, or empty. */
    Optional<Branch> deterministicActiveBranch(UUID organizationId) {
        return branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organizationId).stream().findFirst();
    }

    /**
     * The branch authorization seam for successor tasks: the branch must
     * exist, be active, and belong to the given organization.
     */
    public Branch requireActiveBranchInOrganization(UUID organizationId, UUID branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(BranchAccessService::refused);
        if (!branch.isActive() || !branch.getOrganization().getId().equals(organizationId)) {
            throw refused();
        }
        return branch;
    }

    /**
     * A BRANCH assignment acts on its own fixed branch only: a differing
     * requested branch is refused, the fixed branch must be active and
     * belong to the assignment's organization.
     */
    Branch requireFixedAssignmentBranch(ActingAssignment assignment, UUID organizationId, UUID requestedBranchId) {
        Branch branch = assignment.getBranch();
        if (branch == null) {
            throw refused();
        }
        if (requestedBranchId != null && !requestedBranchId.equals(branch.getId())) {
            throw refused();
        }
        return requireActiveBranchInOrganization(organizationId, branch.getId());
    }

    /**
     * A DEPARTMENT assignment derives its branch from the department: the
     * department and its branch must exist and be consistent, the branch must
     * be active and belong to the assignment's organization, and a differing
     * requested branch is refused.
     */
    Branch requireDepartmentBranch(ActingAssignment assignment, UUID organizationId, UUID requestedBranchId) {
        Department department = assignment.getDepartment();
        if (department == null || department.getBranch() == null) {
            throw refused();
        }
        UUID departmentBranchId = department.getBranch().getId();
        if (requestedBranchId != null && !requestedBranchId.equals(departmentBranchId)) {
            throw refused();
        }
        return requireActiveBranchInOrganization(organizationId, departmentBranchId);
    }

    /** Allowlisted display label for assignment views; null-safe for absent rows. */
    String organizationLabel(UUID organizationId) {
        return organizations.findById(organizationId).map(HospitalOrganization::getName).orElse(null);
    }

    /** Allowlisted display label for assignment views; null-safe for absent rows. */
    String branchLabel(UUID branchId) {
        return branches.findById(branchId).map(Branch::getName).orElse(null);
    }

    /** Allowlisted display label for assignment views; null-safe for absent rows. */
    String departmentLabel(UUID departmentId) {
        return departments.findById(departmentId).map(Department::getName).orElse(null);
    }
}

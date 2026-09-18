package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The narrow reusable server-side hierarchy authorization seam (docs/plan3.md
 * Task 3; Phase 5 T048). Every branch decision — login's deterministic
 * default branch, the context switch's target validation, and the
 * per-request reload — flows through here, so successor tasks reuse exactly
 * one server-verified hierarchy contract. It validates actual UUID
 * scope/context only: human codes and any client-sent role claim are never
 * authorization evidence.
 *
 * <p>Phase 5 full-chain rule: a branch is usable only through its complete
 * active ancestor chain — the branch itself active, its hospital facility
 * existing and active, and that facility belonging to the assignment's
 * organization. Client-supplied hospital/branch identifiers select only
 * among server-issued targets of the acting assignment; they can never
 * widen authority, because every id is re-resolved from server state and
 * the whole chain must hold at once. Inactive ancestors therefore invalidate
 * descendant use immediately on the next reload (FR-004).</p>
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
    private final HospitalFacilityRepository hospitals;

    public BranchAccessService(HospitalOrganizationRepository organizations,
                               BranchRepository branches,
                               DepartmentRepository departments,
                               HospitalFacilityRepository hospitals) {
        this.organizations = organizations;
        this.branches = branches;
        this.departments = departments;
        this.hospitals = hospitals;
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

    /**
     * First usable branch of the organization in deterministic code order:
     * the branch must be active AND its hospital ancestor must be active
     * (T048), so an inactive facility is never selected into a new context.
     * The usable set is resolved with two repository reads — the reload path
     * runs detached (OSIV off), so only proxy identifiers are read here,
     * never lazy state.
     */
    Optional<Branch> deterministicActiveBranch(UUID organizationId) {
        Set<UUID> activeHospitalIds = hospitals.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organizationId)
                .stream().map(HospitalFacility::getId).collect(Collectors.toSet());
        return branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organizationId).stream()
                .filter(branch -> activeHospitalIds.contains(branch.getHospital().getId()))
                .findFirst();
    }

    /**
     * The full-chain branch authorization seam (T048): the branch must
     * exist, be active, belong to the given organization, and sit under an
     * active hospital of that same organization. All state is read through
     * repositories — the per-request reload is detached, so lazy proxies
     * only ever contribute their identifiers.
     */
    public Branch requireActiveBranchInOrganization(UUID organizationId, UUID branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(BranchAccessService::refused);
        if (!branch.isActive() || branch.getHospital() == null) {
            throw refused();
        }
        HospitalFacility hospital = hospitals.findById(branch.getHospital().getId())
                .orElseThrow(BranchAccessService::refused);
        if (!hospital.isActive() || !hospital.getOrganization().getId().equals(organizationId)) {
            throw refused();
        }
        return branch;
    }

    /**
     * The hospital authorization seam (Phase 5): the hospital must exist,
     * be active, and belong to the given organization. Inactive hospitals
     * cannot be selected for a new context.
     */
    public HospitalFacility requireActiveHospitalInOrganization(UUID organizationId, UUID hospitalId) {
        HospitalFacility hospital = hospitals.findByIdAndOrganizationId(hospitalId, organizationId)
                .orElseThrow(BranchAccessService::refused);
        if (!hospital.isActive()) {
            throw refused();
        }
        return hospital;
    }

    /**
     * The client-supplied hospital id must select exactly the given fixed
     * hospital of the assignment: a differing id is a widening attempt and
     * shares the generic refusal (T050).
     */
    void requireSameHospital(UUID fixedHospitalId, UUID requestedHospitalId) {
        if (requestedHospitalId != null && !requestedHospitalId.equals(fixedHospitalId)) {
            throw refused();
        }
    }

    /** First active branch of one hospital in deterministic code order, or empty. */
    Optional<Branch> deterministicActiveBranchInHospital(UUID hospitalId) {
        return branches.findByHospitalIdAndActiveTrueOrderByCodeAsc(hospitalId).stream().findFirst();
    }

    /**
     * The hospital-scoped branch authorization seam (T048 full chain): the
     * branch must exist, belong to exactly that hospital, be active, and
     * that hospital must itself be active.
     */
    public Branch requireActiveBranchInHospital(UUID hospitalId, UUID branchId) {
        HospitalFacility hospital = hospitals.findById(hospitalId).orElseThrow(BranchAccessService::refused);
        if (!hospital.isActive()) {
            throw refused();
        }
        Branch branch = branches.findByIdAndHospitalId(branchId, hospitalId)
                .orElseThrow(BranchAccessService::refused);
        if (!branch.isActive()) {
            throw refused();
        }
        return branch;
    }

    /**
     * A BRANCH assignment acts on its own fixed hospital/branch pair only:
     * a differing requested hospital or branch is refused, and the fixed
     * branch must pass the full active chain (T048/T050).
     */
    Branch requireFixedAssignmentBranch(ActingAssignment assignment, UUID organizationId,
                                        UUID requestedHospitalId, UUID requestedBranchId) {
        Branch branch = assignment.getBranch();
        if (branch == null || branch.getHospital() == null) {
            throw refused();
        }
        requireSameHospital(branch.getHospital().getId(), requestedHospitalId);
        if (requestedBranchId != null && !requestedBranchId.equals(branch.getId())) {
            throw refused();
        }
        return requireActiveBranchInOrganization(organizationId, branch.getId());
    }

    /**
     * A DEPARTMENT assignment derives its branch from the department: the
     * department and its branch must exist and be consistent, the requested
     * hospital/branch ids (when supplied) must match that derived chain,
     * and the full active chain must hold (T048/T050). The branch entity is
     * re-read through the repository because the per-request reload is
     * detached and the department's lazy branch proxy contributes its id
     * only.
     */
    Branch requireDepartmentBranch(ActingAssignment assignment, UUID organizationId,
                                   UUID requestedHospitalId, UUID requestedBranchId) {
        Department department = assignment.getDepartment();
        if (department == null || department.getBranch() == null) {
            throw refused();
        }
        UUID departmentBranchId = department.getBranch().getId();
        Branch branch = branches.findById(departmentBranchId).orElseThrow(BranchAccessService::refused);
        if (branch.getHospital() == null) {
            throw refused();
        }
        requireSameHospital(branch.getHospital().getId(), requestedHospitalId);
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
    String hospitalLabel(UUID hospitalId) {
        return hospitals.findById(hospitalId).map(HospitalFacility::getName).orElse(null);
    }

    /** Allowlisted display label for assignment views; null-safe for absent rows. */
    String departmentLabel(UUID departmentId) {
        return departments.findById(departmentId).map(Department::getName).orElse(null);
    }
}

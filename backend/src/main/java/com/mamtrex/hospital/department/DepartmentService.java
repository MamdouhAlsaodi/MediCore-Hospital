package com.mamtrex.hospital.department;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Branch-owned department rules (docs/plan3.md Task 2). Creation resolves
 * the branch reference through the branch repository (unknown is the
 * shared 404), refuses an inactive branch with the controlled conflict,
 * trims every accepted value, and refuses a duplicate code inside one
 * branch twice: the cause-free duplicate pre-check maps to the safe 409
 * and the {@code (branch_id, code)} DB unique constraint is the
 * concurrency backstop whose translated violation answers the generic
 * conflict. The same code in two branches is two legitimate rows. Every
 * real create/delete owns exactly one audit event; refused writes record
 * nothing. Rows with no branch are the deliberate legacy transition seam:
 * the normalized list exposes assigned rows only, and get/delete treat an
 * unassigned row as absent — legacy rows are never disclosed or mutated
 * here. Branch scoping of other resource families is explicitly NOT part
 * of this task.
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
        Branch branch = branches.findById(request.branchId())
                .orElseThrow(() -> new NotFoundException("Branch not found: " + request.branchId()));
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
     * Assigned rows only, deterministic order. Without a filter the list
     * covers every assigned row because assignment-scoped authorization
     * arrives in a later task; a supplied unknown branch is the shared 404
     * — never a silently empty list.
     */
    @Transactional(readOnly = true)
    public List<DepartmentDtos.DepartmentResponse> list(UUID branchId) {
        if (branchId == null) {
            return departments.findByBranchIsNotNullOrderByCodeAscIdAsc().stream()
                    .map(DepartmentDtos.DepartmentResponse::from)
                    .toList();
        }
        Branch branch = branches.findById(branchId)
                .orElseThrow(() -> new NotFoundException("Branch not found: " + branchId));
        return departments.findByBranchIdOrderByCodeAsc(branch.getId()).stream()
                .map(DepartmentDtos.DepartmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DepartmentDtos.DepartmentResponse get(UUID id) {
        return departments.findById(id)
                .filter(department -> department.getBranch() != null)
                .map(DepartmentDtos.DepartmentResponse::from)
                .orElseThrow(() -> new NotFoundException("Department not found: " + id));
    }

    public void delete(UUID id) {
        Department department = departments.findById(id)
                .filter(candidate -> candidate.getBranch() != null)
                .orElseThrow(() -> new NotFoundException("Department not found: " + id));
        departments.delete(department);
        audit.record("DELETE", "Department", id.toString(), "deleted");
    }
}

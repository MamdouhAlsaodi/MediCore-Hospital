package com.mamtrex.hospital.staff;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.shared.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP boundary for /api/staff (docs/plan1.md Task 3). Staff records are the
 * professional directory behind appointment professional selection, so
 * responses expose selection data only and never persistence internals
 * (version, createdAt, updatedAt) or any mutable JPA entity. Shared
 * client-error mapping lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 *
 * <p>Since docs/plan3.md Task 4 every staff record is branch-owned: the
 * create derives ownership from the acting context (never client input),
 * and the list/get/delete surface resolves only inside the acting branch —
 * a cross-branch id answers the shared 404, indistinguishable from
 * nonexistent. This boundary keeps its repository-adjacent shape because a
 * staff service extraction is outside this packet's allowed paths; the
 * workflow rules remain few and mirrored by {@code PatientService} and
 * {@code AppointmentService}.</p>
 */
@RestController
@RequestMapping("/api/staff")
public class StaffMemberController {

    private final StaffMemberRepository repo;
    private final BranchRepository branches;
    private final AuditService audit;

    public StaffMemberController(StaffMemberRepository repo, BranchRepository branches, AuditService audit) {
        this.repo = repo;
        this.branches = branches;
        this.audit = audit;
    }

    public record Request(@jakarta.validation.constraints.NotBlank String employeeCode,
                          @jakarta.validation.constraints.NotBlank String fullName,
                          @jakarta.validation.constraints.NotBlank String profession,
                          @jakarta.validation.constraints.NotBlank String licenseNumber,
                          @jakarta.validation.constraints.NotBlank String department) {}

    @PostMapping
    public StaffMemberDtos.StaffMemberResponse create(@Valid @RequestBody Request r) {
        var e = repo.save(new StaffMember(actingBranch(), r.employeeCode(), r.fullName(),
                r.profession(), r.licenseNumber(), r.department()));
        audit.record("CREATE", "StaffMember", e.getId().toString(), "created");
        return StaffMemberDtos.StaffMemberResponse.from(e);
    }

    @GetMapping
    public List<StaffMemberDtos.StaffMemberResponse> list() {
        return repo.findByBranchIdOrderByIdAsc(actingBranch().getId()).stream()
                .map(StaffMemberDtos.StaffMemberResponse::from).toList();
    }

    @GetMapping("/{id}")
    public StaffMemberDtos.StaffMemberResponse get(@PathVariable UUID id) {
        return repo.findByIdAndBranchId(id, actingBranch().getId())
                .map(StaffMemberDtos.StaffMemberResponse::from)
                .orElseThrow(() -> new NotFoundException("StaffMember not found: " + id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        StaffMember staffMember = repo.findByIdAndBranchId(id, actingBranch().getId())
                .orElseThrow(() -> new NotFoundException("StaffMember not found: " + id));
        repo.delete(staffMember);
        audit.record("DELETE", "StaffMember", id.toString(), "deleted");
    }

    private Branch actingBranch() {
        return branches.findById(actingContext().branchId())
                .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
    }

    /**
     * Fail-closed seam: the JWT filter guarantees an {@link ActingContext}
     * principal and an existing active selected branch for every authorized
     * request; anything else is refused, never guessed.
     */
    private static ActingContext actingContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActingContext context) {
            return context;
        }
        throw new AccessDeniedException("No acting context is available");
    }
}

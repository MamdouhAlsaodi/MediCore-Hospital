package com.mamtrex.hospital.staff;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.shared.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP boundary for /api/staff (docs/plan1.md Task 3): parses requests and
 * maps entities to {@link StaffMemberDtos.StaffMemberResponse}. This endpoint
 * family is the professional directory behind appointment professional
 * selection, so it participates in the Patient Journey DTO contract. Shared
 * client-error mapping lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffMemberController {

    private final StaffMemberRepository repo;
    private final AuditService audit;

    public StaffMemberController(StaffMemberRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record Request(@jakarta.validation.constraints.NotBlank String employeeCode,
                          @jakarta.validation.constraints.NotBlank String fullName,
                          @jakarta.validation.constraints.NotBlank String profession,
                          @jakarta.validation.constraints.NotBlank String licenseNumber,
                          @jakarta.validation.constraints.NotBlank String department) {}

    @PostMapping
    public StaffMemberDtos.StaffMemberResponse create(@Valid @RequestBody Request r) {
        var e = repo.save(new StaffMember(r.employeeCode(), r.fullName(), r.profession(), r.licenseNumber(), r.department()));
        audit.record("CREATE", "StaffMember", e.getId().toString(), "created");
        return StaffMemberDtos.StaffMemberResponse.from(e);
    }

    @GetMapping
    public List<StaffMemberDtos.StaffMemberResponse> list() {
        return repo.findAll().stream().map(StaffMemberDtos.StaffMemberResponse::from).toList();
    }

    @GetMapping("/{id}")
    public StaffMemberDtos.StaffMemberResponse get(@PathVariable UUID id) {
        return repo.findById(id).map(StaffMemberDtos.StaffMemberResponse::from)
                .orElseThrow(() -> new NotFoundException("StaffMember not found: " + id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        if (!repo.existsById(id)) {
            throw new NotFoundException("StaffMember not found: " + id);
        }
        repo.deleteById(id);
        audit.record("DELETE", "StaffMember", id.toString(), "deleted");
    }
}

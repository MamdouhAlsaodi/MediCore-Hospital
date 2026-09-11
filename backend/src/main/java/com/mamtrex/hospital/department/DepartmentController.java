package com.mamtrex.hospital.department;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper over {@link DepartmentService} (docs/plan3.md
 * Task 2). Parses requests, delegates reference resolution, the
 * active-branch rule, uniqueness, trimming, and audit recording to the
 * service, and returns {@link DepartmentDtos.DepartmentResponse} — never a
 * JPA entity. Route authorization lives in SecurityConfig (reads keep the
 * ADMIN/HR family roles; hierarchy writes are ADMIN-only); shared
 * client-error mapping (404/400/409) lives in the shared
 * GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentService service;

    public DepartmentController(DepartmentService service) {
        this.service = service;
    }

    @PostMapping
    public DepartmentDtos.DepartmentResponse create(@Valid @RequestBody DepartmentDtos.CreateDepartmentRequest r) {
        return service.create(r);
    }

    @GetMapping
    public List<DepartmentDtos.DepartmentResponse> list(@RequestParam(required = false) UUID branchId) {
        return service.list(branchId);
    }

    @GetMapping("/{id}")
    public DepartmentDtos.DepartmentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

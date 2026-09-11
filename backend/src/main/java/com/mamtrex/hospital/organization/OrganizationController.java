package com.mamtrex.hospital.organization;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper for the hierarchy surface (docs/plan3.md Task 2):
 * the organization view and the ADMIN-only branch endpoints. Parses
 * requests, delegates reference resolution, uniqueness, trimming, and
 * audit recording to {@link OrganizationService}, and returns
 * {@link OrganizationDtos} — never JPA entities. Route authorization
 * (ADMIN-only at this stage) lives in SecurityConfig; shared client-error
 * mapping (404/400/409) lives in the shared GlobalExceptionHandler.
 */
@RestController
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping("/api/organization")
    public OrganizationDtos.OrganizationResponse organization() {
        return service.loadOrganization();
    }

    @PostMapping("/api/branches")
    public ResponseEntity<OrganizationDtos.BranchResponse> createBranch(
            @Valid @RequestBody OrganizationDtos.CreateBranchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBranch(request));
    }

    @GetMapping("/api/branches")
    public List<OrganizationDtos.BranchResponse> branches() {
        return service.listBranches();
    }

    @GetMapping("/api/branches/{id}")
    public OrganizationDtos.BranchResponse branch(@PathVariable UUID id) {
        return service.getBranch(id);
    }
}

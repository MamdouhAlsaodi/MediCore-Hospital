package com.mamtrex.hospital.admission;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper over {@link AdmissionService} (docs/plan2.md
 * Task 2): parses requests, delegates reference resolution, the
 * server-owned ADMITTED -> DISCHARGED lifecycle, and audit recording to the
 * service, and returns {@link AdmissionDtos.AdmissionResponse} — never a JPA
 * entity. It owns no repositories and records no audit events; shared
 * client-error mapping (404/400/409) lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/admissions")
public class AdmissionController {

    private final AdmissionService service;

    public AdmissionController(AdmissionService service) {
        this.service = service;
    }

    @PostMapping
    public AdmissionDtos.AdmissionResponse create(@Valid @RequestBody AdmissionDtos.CreateAdmissionRequest r) {
        return service.create(r);
    }

    @GetMapping
    public List<AdmissionDtos.AdmissionResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public AdmissionDtos.AdmissionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/status")
    public AdmissionDtos.AdmissionResponse discharge(@PathVariable UUID id,
                                                     @Valid @RequestBody AdmissionDtos.UpdateAdmissionStatusRequest r) {
        return service.discharge(id, r);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

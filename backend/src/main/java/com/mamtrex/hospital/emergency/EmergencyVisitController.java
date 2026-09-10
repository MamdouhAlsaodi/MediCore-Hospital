package com.mamtrex.hospital.emergency;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper over {@link EmergencyVisitService} (docs/plan2.md
 * Task 3): parses requests, delegates reference resolution, the server-owned
 * WAITING -> IN_TREATMENT | CLOSED lifecycle, and audit recording to the
 * service, and returns {@link EmergencyVisitDtos.EmergencyVisitResponse} —
 * never a JPA entity. It owns no repositories and records no audit events;
 * shared client-error mapping (404/400/409) lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}. The triage
 * label handled here is a neutral 1–5 demo value with no clinical meaning.
 */
@RestController
@RequestMapping("/api/emergency-visits")
public class EmergencyVisitController {

    private final EmergencyVisitService service;

    public EmergencyVisitController(EmergencyVisitService service) {
        this.service = service;
    }

    @PostMapping
    public EmergencyVisitDtos.EmergencyVisitResponse create(@Valid @RequestBody EmergencyVisitDtos.CreateEmergencyVisitRequest r) {
        return service.create(r);
    }

    @GetMapping
    public List<EmergencyVisitDtos.EmergencyVisitResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public EmergencyVisitDtos.EmergencyVisitResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/status")
    public EmergencyVisitDtos.EmergencyVisitResponse updateStatus(@PathVariable UUID id,
                                                                  @Valid @RequestBody EmergencyVisitDtos.UpdateEmergencyVisitStatusRequest r) {
        return service.updateStatus(id, r);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

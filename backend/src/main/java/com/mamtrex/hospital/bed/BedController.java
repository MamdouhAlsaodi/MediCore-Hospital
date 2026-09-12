package com.mamtrex.hospital.bed;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP boundary for /api/beds (docs/plan3.md Task 6): parses requests and
 * maps entities to {@link BedDtos.BedResponse}. Workflow, branch scoping,
 * lifecycle, conflict, and audit rules live in {@link BedService}; shared
 * client-error mapping lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}. The route
 * family keeps its existing four-role server policy in
 * SecurityConfig — Task 6 changes no role rule.
 */
@RestController
@RequestMapping("/api/beds")
public class BedController {

    private final BedService service;

    public BedController(BedService service) {
        this.service = service;
    }

    @PostMapping
    public BedDtos.BedResponse create(@Valid @RequestBody BedDtos.CreateBedRequest r) {
        return BedDtos.BedResponse.from(service.create(r.ward(), r.room(), r.bedNumber()));
    }

    @GetMapping
    public List<BedDtos.BedResponse> list() {
        return service.list().stream().map(BedDtos.BedResponse::from).toList();
    }

    @GetMapping("/{id}")
    public BedDtos.BedResponse get(@PathVariable UUID id) {
        return BedDtos.BedResponse.from(service.get(id));
    }

    @PutMapping("/{id}/status")
    public BedDtos.BedResponse transitionStatus(@PathVariable UUID id,
                                                @Valid @RequestBody BedDtos.UpdateBedStatusRequest r) {
        return BedDtos.BedResponse.from(service.transitionStatus(id, r.status()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

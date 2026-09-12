package com.mamtrex.hospital.staff;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper over {@link StaffAvailabilityService} (docs/plan3.md
 * Task 9): parses requests, delegates the branch/professional verification,
 * interval rules, and audit recording to the service, and returns the
 * allowlisted {@link StaffAvailabilityDtos.AvailabilityResponse}. It owns no
 * repositories and records no audit events; shared client-error mapping
 * lives in {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 *
 * <p>Authorization is inherited unchanged from the existing method-level and
 * route rules: availability writes ride the non-GET {@code /api/staff/**}
 * ADMIN/HR rule and the read rides the {@code GET /api/staff/**}
 * ADMIN/HR/RECEPTIONIST family — the primary scheduling role keeps its read
 * while management stays ADMIN/HR. No new security rule was added.</p>
 */
@RestController
@RequestMapping("/api/staff/{staffMemberId}/availability")
public class StaffAvailabilityController {

    private final StaffAvailabilityService service;

    public StaffAvailabilityController(StaffAvailabilityService service) {
        this.service = service;
    }

    @PostMapping
    public StaffAvailabilityDtos.AvailabilityResponse create(
            @PathVariable UUID staffMemberId,
            @Valid @RequestBody StaffAvailabilityDtos.CreateAvailabilityRequest r) {
        return service.create(staffMemberId, r);
    }

    @GetMapping
    public List<StaffAvailabilityDtos.AvailabilityResponse> window(
            @PathVariable UUID staffMemberId,
            @Valid StaffAvailabilityDtos.AvailabilityWindow window) {
        return service.window(staffMemberId, window.from(), window.to());
    }
}

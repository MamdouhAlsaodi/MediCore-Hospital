package com.mamtrex.hospital.appointment;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Narrow HTTP/DTO mapper over {@link AppointmentService} (docs/plan1.md
 * Task 4): parses requests, delegates reference resolution, creation and
 * deletion rules, and audit recording to the service, and returns
 * {@link AppointmentDtos.AppointmentResponse}. It owns no repositories and
 * records no audit events; shared client-error mapping lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @PostMapping
    public AppointmentDtos.AppointmentResponse create(@Valid @RequestBody AppointmentDtos.CreateAppointmentRequest r) {
        return service.create(r);
    }

    @GetMapping
    public List<AppointmentDtos.AppointmentResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public AppointmentDtos.AppointmentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

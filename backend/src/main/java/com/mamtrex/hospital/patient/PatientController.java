package com.mamtrex.hospital.patient;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP boundary for /api/patients (docs/plan1.md Task 3): parses requests
 * and maps entities to {@link PatientDtos.PatientResponse}. Workflow and
 * audit rules live in {@link PatientService}; shared client-error mapping
 * lives in {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @PostMapping
    public PatientDtos.PatientResponse create(@Valid @RequestBody PatientDtos.CreatePatientRequest r) {
        return PatientDtos.PatientResponse.from(
                service.create(r.medicalRecordNumber(), r.fullName(), r.dateOfBirth(), r.sex(),
                        r.phone(), r.email(), r.nationalId(), r.address()));
    }

    @GetMapping("/{id}")
    public PatientDtos.PatientResponse get(@PathVariable UUID id) {
        return PatientDtos.PatientResponse.from(service.get(id));
    }

    @GetMapping
    public List<PatientDtos.PatientResponse> list(@RequestParam(required = false) String q) {
        return service.list(q).stream().map(PatientDtos.PatientResponse::from).toList();
    }

    @PutMapping("/{id}")
    public PatientDtos.PatientResponse update(@PathVariable UUID id, @Valid @RequestBody PatientDtos.UpdatePatientRequest r) {
        return PatientDtos.PatientResponse.from(service.update(id, r.fullName(), r.phone(), r.email(), r.address()));
    }
}

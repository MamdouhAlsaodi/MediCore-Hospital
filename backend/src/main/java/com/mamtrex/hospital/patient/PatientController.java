package com.mamtrex.hospital.patient;

import com.mamtrex.hospital.shared.ApiError;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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

    /**
     * T068: the duplicate natural-key conflict is owned by this workflow
     * (DuplicateKeyException mapping), so the 409 is documented here; the
     * explicit 200 keeps the derived PatientResponse success schema.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The created patient",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PatientDtos.PatientResponse.class))),
            @ApiResponse(responseCode = "409", description = "A patient with this medical record number already exists "
                    + "(shared ApiError body; no partial write and no overwrite).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public PatientDtos.PatientResponse create(@Valid @RequestBody PatientDtos.CreatePatientRequest r) {
        return PatientDtos.PatientResponse.from(
                service.create(r.medicalRecordNumber(), r.fullName(), r.dateOfBirth(), r.sex(),
                        r.phone(), r.email(), r.nationalId(), r.address()));
    }

    /**
     * Phase 5 US3 (T071): a patient outside the acting hospital's ACTIVE
     * access grants — including any foreign-hospital id — is indistinguishable
     * from a nonexistent one: the same generic 404 with the shared ApiError
     * body, never a scoped error that would disclose existence.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The patient",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PatientDtos.PatientResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown id or a patient the acting hospital "
                    + "holds no active access grant for (shared ApiError body; the two are indistinguishable)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public PatientDtos.PatientResponse get(@PathVariable UUID id) {
        return PatientDtos.PatientResponse.from(service.get(id));
    }

    @GetMapping
    public List<PatientDtos.PatientResponse> list(@RequestParam(required = false) String q) {
        return service.list(q).stream().map(PatientDtos.PatientResponse::from).toList();
    }

    /** Same generic 404 refusal contract as GET (T071). */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated patient",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PatientDtos.PatientResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown id or a patient the acting hospital "
                    + "holds no active access grant for (shared ApiError body)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}")
    public PatientDtos.PatientResponse update(@PathVariable UUID id, @Valid @RequestBody PatientDtos.UpdatePatientRequest r) {
        return PatientDtos.PatientResponse.from(service.update(id, r.fullName(), r.phone(), r.email(), r.address()));
    }
}

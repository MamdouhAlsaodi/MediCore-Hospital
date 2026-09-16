package com.mamtrex.hospital.admission;

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
 * Narrow HTTP/DTO mapper over {@link AdmissionService} (docs/plan2.md
 * Task 2; docs/plan3.md Task 7 adds PUT /{id}/bed): parses requests,
 * delegates reference resolution, the server-owned ADMITTED -> DISCHARGED
 * lifecycle, the atomic bed assignment/transfer, and audit recording to the
 * service, and returns {@link AdmissionDtos.AdmissionResponse} — never a
 * JPA entity or assignment row. It owns no repositories and records no
 * audit events; shared client-error mapping (404/400/409) lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/admissions")
public class AdmissionController {

    private final AdmissionService service;

    public AdmissionController(AdmissionService service) {
        this.service = service;
    }

    /**
     * T068: the reference and bed-state conflicts are owned by this
     * workflow (404 unknown patient/bed reference; 409 selected bed not
     * AVAILABLE), so both are documented on the operation.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The created admission (status ADMITTED, any selected bed held)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AdmissionDtos.AdmissionResponse.class))),
            @ApiResponse(responseCode = "404", description = "The referenced patient or bed does not exist "
                    + "inside the acting branch (shared ApiError body).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The selected bed is not AVAILABLE, so the "
                    + "admission was not created (shared ApiError body; no partial write).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
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

    /**
     * Initial bed assignment or atomic transfer (docs/plan3.md Task 7).
     * T068: the bed-state conflict is owned by this workflow's atomic
     * assignment, so the 409 is documented on the operation.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The admission now holding the assigned bed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AdmissionDtos.AdmissionResponse.class))),
            @ApiResponse(responseCode = "409", description = "The target bed is not AVAILABLE or the admission "
            + "already holds it (shared ApiError body; the previous bed state is unchanged).",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}/bed")
    public AdmissionDtos.AdmissionResponse assignBed(@PathVariable UUID id,
                                                     @Valid @RequestBody AdmissionDtos.AssignBedRequest r) {
        return service.assignBed(id, r);
    }

    /**
     * T068: the lifecycle transition conflict is owned by this workflow
     * (only ADMITTED may become DISCHARGED), so the 409 is documented on
     * the operation.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The discharged admission (status DISCHARGED, held bed released)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AdmissionDtos.AdmissionResponse.class))),
            @ApiResponse(responseCode = "409", description = "The requested transition is not legal from the "
            + "admission's current status (shared ApiError body; the admission is unchanged).",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiError.class)))
    })
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

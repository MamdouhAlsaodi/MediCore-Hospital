package com.mamtrex.hospital.appointment;

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

    /**
     * T068: the reference and scheduling conflicts are owned by this
     * workflow (404 unknown verified reference; 409 outside availability
     * or overlapping window), so both are documented on the operation.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The created appointment",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AppointmentDtos.AppointmentResponse.class))),
            @ApiResponse(responseCode = "404", description = "The referenced patient or professional does not "
                    + "exist inside the acting branch (shared ApiError body; cross-branch references answer "
                    + "the same 404).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The requested window is not contained in one "
                    + "availability interval or overlaps an existing non-cancelled appointment (shared "
                    + "ApiError body; no partial write).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
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

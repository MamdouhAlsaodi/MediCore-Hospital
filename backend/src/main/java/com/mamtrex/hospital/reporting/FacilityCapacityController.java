package com.mamtrex.hospital.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.mamtrex.hospital.shared.InvalidParameterValueException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;

@RestController
@RequestMapping("/api/capacity")
public class FacilityCapacityController {
    private final FacilityCapacityService service;
    public FacilityCapacityController(FacilityCapacityService service) { this.service = service; }

    /** No selector can expand the acting authority; this read has no client-supplied scope. */
    private static void rejectSelectors(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new InvalidParameterValueException("Capacity selectors are not supported");
    }

    @Operation(operationId = "getActingBranchCapacity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capacity for the acting branch", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FacilityCapacityService.CapacitySummary.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    @GetMapping("/branch")
    public FacilityCapacityService.CapacitySummary branch(HttpServletRequest request) {
        rejectSelectors(request);
        return service.branch();
    }

    @Operation(operationId = "getActingHospitalCapacity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capacity for active authorized branches of the acting hospital", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FacilityCapacityService.HospitalCapacitySummary.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Acting scope denied")
    })
    @GetMapping("/hospital")
    public FacilityCapacityService.HospitalCapacitySummary hospital(HttpServletRequest request) {
        rejectSelectors(request);
        return service.hospital();
    }

    @Operation(operationId = "getAuthorizedNetworkCapacity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capacity for the authorized network", content = @Content(mediaType = "application/json", array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = FacilityCapacityService.HospitalCapacitySummary.class)))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Acting scope denied")
    })
    @GetMapping("/network")
    public List<FacilityCapacityService.HospitalCapacitySummary> network(HttpServletRequest request) {
        rejectSelectors(request);
        return service.network();
    }
}

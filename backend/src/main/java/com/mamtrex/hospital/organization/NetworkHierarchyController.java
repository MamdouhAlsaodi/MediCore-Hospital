package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow HTTP/DTO mapper for the authorized network hierarchy (Phase 5
 * US1, T035): exactly one mapping, {@code GET /api/network/hierarchy}, with
 * no path/query parameters — a client can never supply hierarchy
 * identifiers for expansion. Scope derivation and ordering live in
 * {@link NetworkHierarchyService}; route authorization (authenticated, all
 * roles, slice derived per acting scope) lives in SecurityConfig ordered
 * ahead of the ADMIN catch-all; a fail-closed resolution rides the shared
 * security translation to the one generic non-enumerating 403 body.
 */
@RestController
public class NetworkHierarchyController {

    private final NetworkHierarchyService service;

    public NetworkHierarchyController(NetworkHierarchyService service) {
        this.service = service;
    }

    /**
     * The authorized network → hospital → branch slice of the acting
     * context, in deterministic order. Every scope receives exactly its
     * server-derived descendants; foreign identifiers are inexpressible.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hierarchy filtered to the server-derived "
                    + "acting authority: exactly the authorized hospitals and their authorized branches in "
                    + "deterministic order (network scope: all active facilities; hospital scope: the acting "
                    + "hospital; branch/department scope: the acting branch inside its hospital)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = NetworkHierarchyDtos.NetworkHierarchy.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token; "
                    + "the acting session is gone. One generic non-enumerating error body.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The acting authority cannot resolve an "
                    + "authorized hierarchy slice (for example an inactive ancestor). One generic "
                    + "non-enumerating error body that discloses nothing about which hierarchy rows exist.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @Operation(operationId = "getAuthorizedNetworkHierarchy",
            summary = "The authorized network hierarchy of the acting context",
            description = "Derives the network → hospital → branch slice from the server-owned acting "
                    + "assignment and current hierarchy state only. The request takes no identifiers: "
                    + "client-supplied hospital or branch references are never accepted as authorization "
                    + "evidence and can never widen the slice. Inactive ancestors fail closed.")
    @GetMapping("/api/network/hierarchy")
    public ResponseEntity<NetworkHierarchyDtos.NetworkHierarchy> hierarchy(
            @AuthenticationPrincipal ActingContext acting) {
        return ResponseEntity.ok(service.hierarchy(acting));
    }
}

package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.auth.ActingContext;
import jakarta.validation.Valid;
import com.mamtrex.hospital.shared.ApiError;
import com.mamtrex.hospital.shared.InvalidParameterValueException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The transfer lifecycle HTTP surface (specs/005 tasks T097; FR-016, FR-020):
 * reads are authenticated and service-scoped; every state-changing call
 * requires the {@code Idempotency-Key} header (missing/blank is a 400
 * before any mutation). Route-level rules in {@code SecurityConfig} keep
 * the family reachable for the operational roles; the service owns the
 * server-side source/destination role and hospital matrix, so a foreign
 * hospital receives the same generic 404 as an unknown transfer.
 */
@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers", description = "Inter-hospital transfer lifecycle (synthetic, non-clinical)")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @Operation(operationId = "requestTransfer", summary = "Request an inter-hospital transfer",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Transfer created in REQUESTED state"),
                    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping
    public ResponseEntity<TransferDtos.TransferView> create(
            @Parameter(description = "Bounded idempotency key", required = true)
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TransferDtos.CreateTransferRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey, httpRequest);
        var outcome = service.create(actingContext(), idempotencyKey, request);
        return respond(outcome, 201);
    }

    @Operation(operationId = "listAuthorizedTransfers",
            summary = "List transfers visible to the acting hospital",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Scoped transfer list"),
                    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @GetMapping
    public List<TransferDtos.TransferView> list(
            @RequestParam(name = "status", required = false) TransferStatus status) {
        return service.list(actingContext(), status);
    }

    @Operation(operationId = "getAuthorizedTransfer",
            summary = "Fetch one transfer visible to the acting hospital",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Scoped transfer view"),
                    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/{id}")
    public TransferDtos.TransferView get(@PathVariable UUID id) {
        return service.get(actingContext(), id);
    }

    @Operation(operationId = "acceptTransfer", summary = "Accept and reserve the destination bed atomically",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Accepted transfer with reservation"),
                    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/accept")
    public ResponseEntity<TransferDtos.TransferView> accept(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TransferDtos.AcceptTransferRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey, httpRequest);
        return respond(service.accept(actingContext(), idempotencyKey, id, request), 200);
    }

    @Operation(operationId = "rejectTransfer", summary = "Reject the transfer (terminal)",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Rejected transfer"),
                    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/reject")
    public ResponseEntity<TransferDtos.TransferView> reject(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TransferDtos.TransitionReasonRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey, httpRequest);
        return respond(service.reject(actingContext(), idempotencyKey, id, request), 200);
    }

    @Operation(operationId = "cancelTransfer", summary = "Cancel the transfer (terminal)",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Cancelled transfer"),
                    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/cancel")
    public ResponseEntity<TransferDtos.TransferView> cancel(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TransferDtos.TransitionReasonRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey, httpRequest);
        return respond(service.cancel(actingContext(), idempotencyKey, id, request), 200);
    }

    @Operation(operationId = "startTransferTransit",
            summary = "Start transit and hand off the source admission/bed",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Transfer moved in transit"),
                    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/start-transit")
    public ResponseEntity<TransferDtos.TransferView> startTransit(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey, httpRequest);
        return respond(service.startTransit(actingContext(), idempotencyKey, id), 200);
    }

    @Operation(operationId = "completeTransfer",
            summary = "Complete: destination admission, reservation consumed, bed occupied",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Completed transfer"),
                    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))), @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/complete")
    public ResponseEntity<TransferDtos.TransferView> complete(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey, httpRequest);
        return respond(service.complete(actingContext(), idempotencyKey, id), 200);
    }

    // ------------------------------------------------------------- internals

    private static ActingContext actingContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActingContext context) {
            return context;
        }
        throw new com.mamtrex.hospital.shared.NotFoundException("Not Found");
    }

    /** The idempotency boundary: a state-changing call without a bounded key is a 400 before anything runs. */
    private static void requireIdempotencyKey(String idempotencyKey, HttpServletRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > com.mamtrex.hospital.idempotency.IdempotencyService.MAX_KEY_LENGTH) {
            throw new InvalidParameterValueException(
                    "A state-changing transfer call requires an Idempotency-Key header of at most "
                            + com.mamtrex.hospital.idempotency.IdempotencyService.MAX_KEY_LENGTH + " characters");
        }
    }

    private static ResponseEntity<TransferDtos.TransferView> respond(TransferService.Outcome outcome,
                                                                     int originalStatus) {
        int status = outcome.replay() && outcome.replayHttpStatus() != null
                ? outcome.replayHttpStatus()
                : originalStatus;
        return ResponseEntity.status(status).body(outcome.view());
    }

    /** A missing required header is a client fault: the shared 400 shape, never a 500. */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(org.springframework.web.bind.MissingRequestHeaderException ex,
                                           HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "Validation Error",
                "A state-changing transfer call requires an Idempotency-Key header", request.getRequestURI()));
    }

    /** Wrong-role refusals are the generic 403; wrong-hospital refusals stay the generic 404. */
    @ExceptionHandler(TransferAuthorizationService.UnauthorizedRoleException.class)
    ResponseEntity<ApiError> unauthorizedRole(TransferAuthorizationService.UnauthorizedRoleException ex,
                                              HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ApiError(Instant.now(), 403,
                "Forbidden", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TransferAuthorizationService.ForeignHospitalScopeException.class)
    ResponseEntity<ApiError> foreignScope(TransferAuthorizationService.ForeignHospitalScopeException ex,
                                          HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ApiError(Instant.now(), 404,
                "Not Found", ex.getMessage(), request.getRequestURI()));
    }
}

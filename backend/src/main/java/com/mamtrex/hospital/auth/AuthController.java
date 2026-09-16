package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.shared.ApiError;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Thin authentication surface (docs/plan3.md Task 3): login verifies the
 * credentials and selects the deterministic acting assignment; the
 * authenticated context switch validates a subject-owned assignment and
 * issues a replacement token. All resolution and validation lives in
 * {@link ActingContextService} and {@link BranchAccessService}. Bad
 * credentials, disabled accounts, and accounts without a valid assignment
 * share one non-enumerating 401; context refusals share one controlled 403
 * in the shared ApiError shape.
 *
 * <p>Phase 4 (T061, FR-009): the login surface is bounded by the
 * {@link LoginRateLimiter}, keyed by the request's direct socket address.
 * A blocked address receives one generic, non-enumerating 429 with a
 * {@code Retry-After} hint before any credential work happens; failures are
 * recorded per address and a success clears them, so recovery is both
 * immediate (after success) and automatic (after the configured window).
 * Rejections and failures create no audit events and leak nothing.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Generic rate-limit refusal: one controlled exception shape for the 429 mapping. */
    static final class LoginRateLimitedException extends RuntimeException {
    }

    private final ActingContextService sessions;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(ActingContextService sessions, LoginRateLimiter loginRateLimiter) {
        this.sessions = sessions;
        this.loginRateLimiter = loginRateLimiter;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record ContextSwitchRequest(@NotNull UUID assignmentId, UUID branchId) {}

    @ApiResponses({
            /** The verified session: token, enabled assignments, and the selected acting context. */
            @ApiResponse(responseCode = "200", description = "The verified acting-context session",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ActingContextService.Session.class))),
            /** One non-enumerating body for wrong password, disabled account, and no valid assignment. */
            @ApiResponse(responseCode = "401", description = "Invalid credentials, disabled account, or no valid "
                    + "acting assignment. The body is one generic non-enumerating error field.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object"))),
            /** One non-enumerating body for a rate-limited address, with the window as a Retry-After hint. */
            @ApiResponse(responseCode = "429", description = "Too many failed attempts from the requesting address. "
                    + "The body is one generic non-enumerating error field.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object")),
                    headers = @Header(name = "Retry-After",
                            schema = @Schema(type = "string"),
                            description = "Seconds until the address may retry"))
    })
    @PostMapping("/login")
    public ActingContextService.Session login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        // Direct socket address only — no trusted-proxy configuration exists
        // in this system, so forwarded headers are never consulted (T061).
        String address = httpRequest.getRemoteAddr();
        if (loginRateLimiter.isBlocked(address)) {
            throw new LoginRateLimitedException();
        }
        try {
            ActingContextService.Session session = sessions.login(request.username(), request.password());
            loginRateLimiter.recordSuccess(address);
            return session;
        } catch (IllegalArgumentException invalidCredentials) {
            loginRateLimiter.recordFailure(address);
            throw invalidCredentials;
        }
    }

    @ApiResponses({
            /** The replacement session bound to the new acting context. */
            @ApiResponse(responseCode = "200", description = "The replacement acting-context session",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ActingContextService.Session.class))),
            /**
             * T068: the 403 is owned by this controller's contextRefused handler,
             * which answers with the shared ApiError shape — not the generic
             * security-error body — so it is documented on the operation itself.
             */
            @ApiResponse(responseCode = "403", description = "The switch to the requested assignment is refused "
            + "(unknown, disabled, or out-of-scope target). Shared ApiError body with a controlled, "
            + "non-widening message.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/context")
    public ActingContextService.Session switchContext(@AuthenticationPrincipal ActingContext actor,
                                                      @Valid @RequestBody ContextSwitchRequest request) {
        return sessions.switchContext(actor, request.assignmentId(), request.branchId());
    }

    /**
     * Maps rejected logins to 401 with a non-enumerating message; scoped to this controller.
     * (T068: the 401/429 contract is documented on the operation because it is
     * owned by this controller's exception handlers, not by the shared
     * framework mappings — the body is one generic error field, never an
     * account enumeration.)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid username or password."));
    }

    /**
     * Maps a blocked direct address to one generic, non-enumerating 429 with
     * a {@code Retry-After} hint of the configured window in seconds. The
     * body is identical for every blocked caller and carries no account or
     * count information.
     */
    @ExceptionHandler(LoginRateLimitedException.class)
    ResponseEntity<Map<String, String>> loginRateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, loginRateLimiter.windowSeconds())))
                .body(Map.of("error", "Too many failed attempts. Try again later."));
    }

    /** Maps context refusals to the shared ApiError shape with the controlled non-enumerating message. */
    @ExceptionHandler(BranchAccessService.RefusedException.class)
    ResponseEntity<ApiError> contextRefused(BranchAccessService.RefusedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError(Instant.now(), 403, "Forbidden", ex.getMessage(), request.getRequestURI()));
    }
}

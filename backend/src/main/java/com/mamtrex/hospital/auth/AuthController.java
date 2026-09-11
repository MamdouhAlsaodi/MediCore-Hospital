package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.shared.ApiError;
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
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ActingContextService sessions;

    public AuthController(ActingContextService sessions) {
        this.sessions = sessions;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record ContextSwitchRequest(@NotNull UUID assignmentId, UUID branchId) {}

    @PostMapping("/login")
    public ActingContextService.Session login(@Valid @RequestBody LoginRequest request) {
        return sessions.login(request.username(), request.password());
    }

    /** Authenticated: only the bearer token selects whose assignments may be targeted. */
    @PostMapping("/context")
    public ActingContextService.Session switchContext(@AuthenticationPrincipal ActingContext actor,
                                                      @Valid @RequestBody ContextSwitchRequest request) {
        return sessions.switchContext(actor, request.assignmentId(), request.branchId());
    }

    /** Maps rejected logins to 401 with a non-enumerating message; scoped to this controller. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid username or password."));
    }

    /** Maps context refusals to the shared ApiError shape with the controlled non-enumerating message. */
    @ExceptionHandler(BranchAccessService.RefusedException.class)
    ResponseEntity<ApiError> contextRefused(BranchAccessService.RefusedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError(Instant.now(), 403, "Forbidden", ex.getMessage(), request.getRequestURI()));
    }
}

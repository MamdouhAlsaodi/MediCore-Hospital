package com.mamtrex.hospital.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Issues stateless JWT bearer tokens. The response carries only the username and
 * role names required by the client shell; never a password hash or internal entity.
 * Bad credentials are a normal 401 outcome — never a 500 — and the error message is
 * uniform so it does not reveal whether the username or the password was wrong.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAccountRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserAccountRepository repo, PasswordEncoder encoder, JwtService jwt) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String accessToken, String tokenType, String username, List<String> roles) {}

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        UserAccount userAccount = repo.findByUsername(req.username())
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!encoder.matches(req.password(), userAccount.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        List<String> roles = userAccount.getRoles().stream().map(Enum::name).sorted().toList();
        return new LoginResponse(jwt.issue(userAccount), "Bearer", userAccount.getUsername(), roles);
    }

    /** Maps rejected logins to 401 with a non-enumerating message; scoped to this controller. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid username or password."));
    }
}

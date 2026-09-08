package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused authorization integration test. Runs against an isolated in-memory H2
 * database (never the production file store) with disposable runtime secrets.
 * Verifies the endpoint-family role policy: anonymous requests are rejected with
 * 401, ADMIN has full access, and a NURSE is allowed clinical/dashboard endpoints
 * but forbidden from audit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:authz-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + SecurityAuthorizationTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + SecurityAuthorizationTest.TEST_ACCOUNT_PASSWORD
})
class SecurityAuthorizationTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-0123456789abcdef0123456789abcdef";
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-123456";

    private static final String ADMIN = "admin";
    private static final String NURSE = "nurse1";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seedTestAccounts() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        if (accounts.findByUsername(NURSE).isEmpty()) {
            accounts.save(new UserAccount(NURSE, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        }
    }

    @SuppressWarnings("unchecked")
    private String login(String username) {
        ResponseEntity<Map<String, Object>> res = rest.postForEntity(
                "/api/auth/login",
                Map.of("username", username, "password", TEST_ACCOUNT_PASSWORD),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "login should succeed for " + username);
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(username, body.get("username"), "login response must carry the username");
        assertTrue(body.get("roles") instanceof List<?>, "login response must carry role names");
        assertFalse(body.containsKey("passwordHash"), "login response must never expose the hash");
        return (String) body.get("accessToken");
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void anonymousDashboardIsRejected() {
        ResponseEntity<String> res = get("/api/dashboard", null);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    void anonymousAuditIsRejected() {
        ResponseEntity<String> res = get("/api/audit", null);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    void healthIsPublic() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
    }

    @Test
    void adminCanAccessDashboardAndAudit() {
        String token = login(ADMIN);
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/audit", token).getStatusCode());
    }

    @Test
    void nurseCanAccessDashboardAndNursingEndpointButIsForbiddenFromAudit() {
        String token = login(NURSE);
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/nursing-observations", token).getStatusCode());
        ResponseEntity<String> audit = get("/api/audit", token);
        assertEquals(HttpStatus.FORBIDDEN, audit.getStatusCode());
    }
}

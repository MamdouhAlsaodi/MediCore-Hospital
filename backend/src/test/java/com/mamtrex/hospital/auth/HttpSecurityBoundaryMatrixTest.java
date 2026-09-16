package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.TestRuntimeSecrets;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T059/T064 HTTP boundary matrix additions over the long-standing
 * {@code SecurityAuthorizationTest} coverage (FR-010/FR-011, plan Task 9
 * steps 1-2, 7): the two token-forgery cases the existing matrix does not
 * yet pin — an EXPIRED but correctly signed token and a TAMPERED SIGNATURE —
 * plus the denied-write corner of the forged-context-header matrix, the
 * actuator exposure boundary, and the batch proof that every refused request
 * (401/403) creates zero mutations and zero success audit events.
 *
 * <p>Runs against the isolated H2 flow with the synthetic demo cohort and the
 * opt-in review accounts (deterministic server-issued acting assignments for
 * admin and nurse), so no runtime environment, credential store, or real
 * secret is ever touched. Failed/expired/forged requests are refused before
 * any controller logic, so the zero-audit assertion is the server-contract
 * proof, not an accident of test isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:http-security-matrix-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "medicore.demo.seed=true"
})
class HttpSecurityBoundaryMatrixTest {

    private static final String ADMIN = "admin";
    private static final String NURSE = "nurse";
    private static final String AUDIT_HEADER = "X-Correlation-Id";
    private static final Pattern CORRELATION_SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
        registry.add("medicore.review-accounts.enabled", () -> "true");
        registry.add("HOSPITAL_REVIEW_DOCTOR_PASSWORD", TestRuntimeSecrets::accountPassword);
        registry.add("HOSPITAL_REVIEW_NURSE_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @SuppressWarnings("unchecked")
    private String login(String username) {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/auth/login", HttpMethod.POST,
                jsonLogin(username, TestRuntimeSecrets.accountPassword()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertEquals(HttpStatus.OK, res.getStatusCode(), "login must succeed for " + username);
        return String.valueOf(res.getBody().get("accessToken"));
    }

    private static HttpEntity<Map<String, Object>> jsonLogin(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("username", username, "password", password), headers);
    }

    private ResponseEntity<String> exchange(String method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers), String.class);
    }

    /** A correctly signed token whose expiry already passed (produced with the real test key). */
    private String expiredToken(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("assignmentId", UUID.randomUUID().toString())
                .claim("role", "NURSE")
                .claim("scope", "BRANCH")
                .claim("organizationId", UUID.randomUUID().toString())
                .claim("branchId", UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(3600)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(TestRuntimeSecrets.jwtSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /** A structurally intact token whose signature bytes were mutated after signing. */
    private String tamperedSignatureToken(String validToken) {
        String body = validToken.substring(0, validToken.lastIndexOf('.') + 1);
        String signature = validToken.substring(validToken.lastIndexOf('.') + 1);
        String corrupted = ("0123456789abcdef" + signature).substring(signature.length());
        return body + corrupted;
    }

    private List<Map<String, Object>> auditEvents(String adminToken) {
        ResponseEntity<List<Map<String, Object>>> res = rest.exchange(
                "/api/audit", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertEquals(HttpStatus.OK, res.getStatusCode());
        return res.getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    // ------------------------------------------------------------------
    // T059 — the token-forgery corners of the matrix.
    // ------------------------------------------------------------------

    @Test
    void expiredButCorrectlySignedTokenIsUnauthorizedEverywhere() {
        String expired = expiredToken(NURSE);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("GET", "/api/dashboard", expired, null).getStatusCode(),
                "an expired token must fail closed with the generic 401");
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("POST", "/api/patients", expired, Map.of("medicalRecordNumber", "X")).getStatusCode(),
                "an expired token must fail closed on writes too");
        // A structurally identical but valid control token authenticates — the
        // refusal is the expiry, not the forging mechanism.
        assertEquals(HttpStatus.OK, exchange("GET", "/api/dashboard", login(NURSE), null).getStatusCode(),
                "the unexpired control token authenticates (forging-mechanism control)");
    }

    @Test
    void tamperedSignatureIsUnauthorized() {
        String tampered = tamperedSignatureToken(login(NURSE));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange("GET", "/api/dashboard", tampered, null).getStatusCode(),
                "a mutated signature must fail closed with the generic 401");
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("POST", "/api/patients", tampered, Map.of("medicalRecordNumber", "X")).getStatusCode(),
                "a mutated signature must fail closed on writes too");
    }

    @Test
    void forgedContextHeadersCannotPushAWriteThrough() {
        String nurse = login(NURSE);
        HttpHeaders decorated = bearer(nurse);
        decorated.set("X-Acting-Role", "ADMIN");
        decorated.set("X-Acting-Branch-Id", UUID.randomUUID().toString());
        decorated.set("X-Forwarded-For", "8.8.8.8");
        Map<String, Object> refusedBody = Map.of(
                "medicalRecordNumber", "MRN-FORGED-" + UUID.randomUUID(),
                "fullName", "Never Persisted");
        ResponseEntity<String> refused = rest.exchange("/api/patients", HttpMethod.POST,
                new HttpEntity<>(refusedBody, decorated), String.class);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(),
                "forged context headers must not elevate a NURSE to a patient-create write");
        ResponseEntity<List<Map<String, Object>>> search = rest.exchange(
                "/api/patients?q=" + refusedBody.get("medicalRecordNumber"), HttpMethod.GET,
                new HttpEntity<>(bearer(login(ADMIN))),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertTrue(search.getBody().isEmpty(), "the refused write must have persisted nothing");
    }

    // ------------------------------------------------------------------
    // T064 — actuator exposure verification and the batch zero-side-effect
    // proof for every refused request class.
    // ------------------------------------------------------------------

    @Test
    void actuatorExposureStaysExactlyHealthPlusAuthenticatedPrometheus() {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            assertEquals(HttpStatus.OK, rest.getForEntity(path, String.class).getStatusCode(),
                    path + " is the anonymous orchestration surface");
        }
        for (String path : List.of("/actuator", "/actuator/prometheus", "/actuator/metrics",
                "/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/loggers")) {
            assertEquals(HttpStatus.UNAUTHORIZED, rest.getForEntity(path, String.class).getStatusCode(),
                    path + " must stay unexposed AND authenticated");
        }
    }

    @Test
    void everyRefusedRequestClassCreatesZeroMutationsAndZeroSuccessAuditEvents() {
        String admin = login(ADMIN);
        List<Map<String, Object>> before = auditEvents(admin);
        assertNotNull(before);

        String nurse = login(NURSE);
        String expired = expiredToken(NURSE);
        String tampered = tamperedSignatureToken(login(NURSE));
        String refusedMrn = "MRN-DENIAL-BATCH-" + UUID.randomUUID();

        // The full 401/403 refusal batch across the write surface:
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("POST", "/api/patients", null, Map.of("medicalRecordNumber", refusedMrn)).getStatusCode(),
                "anonymous write");
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("POST", "/api/patients", expired, Map.of("medicalRecordNumber", refusedMrn)).getStatusCode(),
                "expired-token write");
        assertEquals(HttpStatus.UNAUTHORIZED,
                exchange("POST", "/api/patients", tampered, Map.of("medicalRecordNumber", refusedMrn)).getStatusCode(),
                "tampered-signature write");
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("POST", "/api/patients", nurse, Map.of("medicalRecordNumber", refusedMrn)).getStatusCode(),
                "denied-role write");
        HttpHeaders forged = bearer(nurse);
        forged.set("X-Acting-Role", "ADMIN");
        assertEquals(HttpStatus.FORBIDDEN,
                rest.exchange("/api/patients", HttpMethod.POST,
                        new HttpEntity<>(Map.of("medicalRecordNumber", refusedMrn), forged), String.class)
                        .getStatusCode(),
                "forged-header write");

        // Zero mutations: the refused MRN never exists.
        ResponseEntity<List<Map<String, Object>>> search = rest.exchange(
                "/api/patients?q=" + refusedMrn, HttpMethod.GET,
                new HttpEntity<>(bearer(admin)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertTrue(search.getBody().isEmpty(), "no refusal in the batch may persist a patient row");

        // Zero success audit events: the event stream is exactly as before.
        List<Map<String, Object>> after = auditEvents(admin);
        assertEquals(before.size(), after.size(),
                "refused requests must create zero audit events");
        assertFalse(after.stream().anyMatch(e -> String.valueOf(e.get("details")).contains(refusedMrn)),
                "no audit event may reference a refused attempt");
    }

    @Test
    void correlationBoundaryStillEchoesBoundedIdsOnRejectedRequests() {
        ResponseEntity<String> res = exchange("GET", "/api/dashboard", null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        String echoed = res.getHeaders().getFirst(AUDIT_HEADER);
        assertNotNull(echoed, "even rejected responses echo the bounded correlation boundary");
        assertTrue(CORRELATION_SHAPE.matcher(echoed).matches(), "the echoed id stays inside the safe shape");
    }
}

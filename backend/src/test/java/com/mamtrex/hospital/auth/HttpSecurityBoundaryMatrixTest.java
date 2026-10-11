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

    // T133 fixture seams: the HOSPITAL-scoped ADMIN is provisioned against
    // the same synthetic demo hierarchy (never a real directory).
    @Autowired com.mamtrex.hospital.auth.UserAccountRepository accounts;
    @Autowired com.mamtrex.hospital.organization.BranchRepository branches;
    @Autowired com.mamtrex.hospital.auth.ActingAssignmentRepository assignments;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;

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

    // ------------------------------------------------------------------
    // Phase 5 US7 (T133) — hospital/network scope and role-route
    // combinations. The demo cohort's admin is ORGANIZATION-scoped, so the
    // HOSPITAL-scoped actor is provisioned here as a dedicated synthetic
    // ADMIN assignment on the demo hospital. The matrix proves: the
    // ADMIN-only routes (audit read, context switch, organization view)
    // refuse the operational roles at the route boundary regardless of
    // scope; the transfer routes stay open to every operational role
    // server-side (the service owns the per-command narrowing); and the
    // scope itself never widens a route — a HOSPITAL-scoped ADMIN receives
    // exactly the same route verdicts as the ORGANIZATION-scoped one, with
    // the visible slice narrowed inside the handler (proven by the audit
    // slice assertions above and the US7 isolation suite).
    // ------------------------------------------------------------------

    @Test
    void hospitalAndNetworkScopeAdminsMeetIdenticalRouteBoundaries() {
        String networkAdmin = login(ADMIN);
        String hospitalAdmin = loginHospitalScopedAdmin();

        // Route verdicts are a ROLE decision at the HTTP boundary; scope only
        // narrows the slice inside the handler. The deliberate exceptions are
        // the network-total routes (dashboard/network, capacity/network),
        // which refuse the HOSPITAL scope server-side with the generic 403 —
        // the documented fail-narrow choice (FR-024), never a leak.
        record Route(String method, String path, int networkStatus, int hospitalStatus) {}
        List<Route> routes = List.of(
                new Route("GET", "/api/audit", HttpStatus.OK.value(), HttpStatus.OK.value()),
                new Route("GET", "/api/organization", HttpStatus.OK.value(), HttpStatus.OK.value()),
                new Route("GET", "/api/network/hierarchy", HttpStatus.OK.value(), HttpStatus.OK.value()),
                new Route("GET", "/api/transfers", HttpStatus.OK.value(), HttpStatus.OK.value()),
                new Route("GET", "/api/patients", HttpStatus.OK.value(), HttpStatus.OK.value()),
                new Route("GET", "/api/dashboard/network", HttpStatus.OK.value(), HttpStatus.FORBIDDEN.value()),
                new Route("GET", "/api/capacity/network", HttpStatus.OK.value(), HttpStatus.FORBIDDEN.value()));

        for (Route route : routes) {
            assertEquals(route.networkStatus(),
                    exchange(route.method(), route.path, networkAdmin, null).getStatusCode().value(),
                    route.method() + " " + route.path + " must behave identically for the network ADMIN");
            assertEquals(route.hospitalStatus(),
                    exchange(route.method(), route.path, hospitalAdmin, null).getStatusCode().value(),
                    route.method() + " " + route.path + " must give the hospital ADMIN its documented verdict — "
                            + "the scope narrows the slice server-side, never widens a route");
        }
        // The hospital ADMIN's audit read is the hospital-wide slice: it stays a
        // strict subset of the network read and never carries the legacy rows.
        List<Map<String, Object>> networkSlice = auditEvents(networkAdmin);
        List<Map<String, Object>> hospitalSlice = auditEvents(hospitalAdmin);
        assertFalse(hospitalSlice.isEmpty(), "the hospital slice carries the demo hospital's own evidence");
        assertTrue(hospitalSlice.size() <= networkSlice.size(),
                "the hospital slice can never exceed the network slice");
        assertFalse(hospitalSlice.stream().anyMatch(e ->
                        "legacy/unassigned".equals(String.valueOf(e.get("branchAttribution")))),
                "legacy rows stay an organization-scope slice");
    }

    @Test
    void operationalRolesStayOffAdminRoutesAcrossScopes() {
        String nurse = login(NURSE);
        // The review nurse is BRANCH-scoped; a HOSPITAL-scoped operational actor
        // would meet the identical route refusals — the boundary is ROLE, not scope.
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/audit", nurse, null).getStatusCode(),
                "the audit read is ADMIN-only at the route boundary");
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/organization", nurse, null).getStatusCode(),
                "the organization view is ADMIN-only at the route boundary");
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/dashboard/network", nurse, null).getStatusCode(),
                "the network command center is ADMIN-only at the route boundary");
        assertEquals(HttpStatus.FORBIDDEN, exchange("POST", "/api/auth/context", nurse,
                Map.of("assignmentId", UUID.randomUUID().toString())).getStatusCode(),
                "the context switch is ADMIN-only at the route boundary");
        // Transfer routes stay reachable for the operational role; the server-side
        // service matrix owns every per-command narrowing (T089).
        assertEquals(HttpStatus.OK, exchange("GET", "/api/transfers", nurse, null).getStatusCode(),
                "the transfer list stays available to the operational role");
    }

    /** A dedicated synthetic ADMIN whose acting assignment is HOSPITAL-scoped on the demo hospital. */
    private String loginHospitalScopedAdmin() {
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String username = "hospital-scope-matrix-" + UUID.randomUUID();
        accounts.save(new com.mamtrex.hospital.auth.UserAccount(username,
                encoder.encode(TestRuntimeSecrets.accountPassword()), java.util.Set.of(com.mamtrex.hospital.auth.Role.ADMIN)));
        var account = accounts.findByUsername(username).orElseThrow();
        // The HOSPITAL factory derives the facility through the branch's lazy
        // relationship, so the assignment is created inside a short transaction
        // against a freshly managed branch (the test thread has no open session).
        var created = transactionTemplate.execute(tx -> {
            var branch = branches.findAllByOrderByCodeAscIdAsc().stream()
                    .filter(b -> b.isActive())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the demo cohort must own one active branch"));
            return assignments.save(com.mamtrex.hospital.auth.ActingAssignment.hospital(
                    account, branch.getOrganization(), com.mamtrex.hospital.auth.Role.ADMIN,
                    branch.getHospital()));
        });
        org.junit.jupiter.api.Assertions.assertNotNull(created);
        return login(username);
    }
}

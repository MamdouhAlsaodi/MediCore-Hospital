package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 3 Task 1 characterization suite (docs/plan3.md Task 1): every test
 * here pins TODAY'S accepted behavior of the branchless repository and the
 * global-role security baseline over real HTTP against an isolated
 * in-memory H2 database with synthetic disposable records only.
 *
 * <p>These are characterization pins, not the future contract: the weak
 * behaviors proven below (missing organization/branch routes, login without
 * assignments, context-header-agnostic reads, raw branchless departments,
 * free-form bed occupancy, accepted appointment overlaps, whole-table
 * dashboard, context-free audit events) are exactly what Plan 3 Tasks 2–11
 * will deliberately change. When one of these tests starts failing after a
 * later task, that failure is the intended signal — update the pin to the
 * new branch-aware contract at that time, never before.</p>
 *
 * <p>Runs against its own uniquely named in-memory H2 database (never the
 * production file store) with long disposable test-only secret and password
 * values, following the established integration-test conventions.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:multibranch-ops-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + MultiBranchOperationsApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + MultiBranchOperationsApiTest.TEST_ACCOUNT_PASSWORD
})
class MultiBranchOperationsApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-multibranch-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-multibranch-01";

    private static final String ADMIN = "mbops-admin";
    private static final String NURSE = "mbops-nurse";

    /** Today's exact flat dashboard key set; no branch/network structure exists. */
    private static final Set<String> DASHBOARD_KEYS = Set.of(
            "patients", "appointments", "admissions", "emergencyVisits", "invoices",
            "openAdmissions", "activeEmergencyVisits",
            "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<Map<String, Object>>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<List<Map<String, Object>>>() {};

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void seedDisposableAccounts() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        if (accounts.findByUsername(NURSE).isEmpty()) {
            accounts.save(new UserAccount(NURSE, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        }
    }

    // ------------------------------------------------------------------
    // 1. No organization/branch surface exists today (absence pin).
    // ------------------------------------------------------------------

    /**
     * ABSENCE CHARACTERIZATION — not the future contract. No controller
     * exists for these routes today: an authenticated ADMIN request passes
     * the SecurityConfig {@code /api/**} ADMIN catch-all, no handler is
     * found, and the framework's error dispatch re-enters the security
     * chain without the JWT filter (a {@code OncePerRequestFilter} skips
     * the ERROR dispatch), so the unauthenticated {@code /error} forward
     * fails {@code anyRequest().authenticated()}. Today's observed outcome
     * is the entry-point 401 with {@code {"error":"authentication
     * required"}} — a framework artifact of the missing route, not a
     * designed not-found contract and not the future invalid-context 401.
     * Task 2 creates the real routes and replaces this pin.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/organization", "/api/branches"})
    void absentOrganizationAndBranchRoutesLeaveAdminOnTheFrameworkErrorDispatch401Today(String path) {
        assertEquals(HttpStatus.UNAUTHORIZED, get(path, login(ADMIN)).getStatusCode(),
                "today no handler exists for " + path + ", so ADMIN lands on the framework's error-dispatch 401");
    }

    /**
     * ABSENCE CHARACTERIZATION — not the future contract. A non-ADMIN never
     * reaches the missing handler: the {@code /api/**} catch-all denies any
     * non-ADMIN account with 403 before routing is even possible today.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/organization", "/api/branches"})
    void proposedOrganizationRoutesDenyNonAdminThroughTheCatchAllToday(String path) {
        assertEquals(HttpStatus.FORBIDDEN, get(path, login(NURSE)).getStatusCode(),
                "today the ADMIN-only catch-all denies " + path + " for a non-ADMIN account");
    }

    // ------------------------------------------------------------------
    // 2. Login is global-role only — no assignments, no acting context.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 3 will intentionally change it): the login
     * response carries exactly the token, token type, username, and global
     * role names — no {@code assignments}, no {@code actingContext}, and
     * never the password hash.
     */
    @Test
    void loginResponseCarriesOnlyGlobalTokenAndRoleFieldsToday() {
        ResponseEntity<Map<String, Object>> response = postJson("/api/auth/login", null, Map.of(
                "username", ADMIN,
                "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the login response must carry a body");
        assertEquals(Set.of("accessToken", "tokenType", "username", "roles"), body.keySet(),
                "today's login response is the global-role shape: token, token type, username, roles");
        assertEquals("Bearer", body.get("tokenType"));
        assertInstanceOf(List.class, body.get("roles"));
        assertTrue(((List<?>) body.get("roles")).contains("ADMIN"),
                "roles must list the account's global role names");
        assertFalse(body.containsKey("assignments"), "no acting assignments exist today");
        assertFalse(body.containsKey("actingContext"), "no acting context exists today");
        assertFalse(body.containsKey("passwordHash"), "the password hash must never be exposed");
    }

    // ------------------------------------------------------------------
    // 3. No acting context is required or honored today.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 3 will intentionally change it): a plain bearer
     * token authorizes existing reads with no assignment/branch header, and
     * proposed context headers carry no scoping effect — the server answers
     * from the same global data either way. Proven against an observable
     * read response, never by decoding the token.
     */
    @Test
    void existingReadsNeedNoActingContextAndArbitraryContextHeadersHaveNoEffectToday() {
        String token = login(ADMIN);
        String patientId = createVerifiedPatientId(token, "ctx");

        ResponseEntity<Map<String, Object>> plain = getJson("/api/patients/" + patientId, token);
        assertEquals(HttpStatus.OK, plain.getStatusCode(),
                "a valid token must authorize the read with no assignment/branch header today");
        Map<String, Object> plainBody = plain.getBody();
        assertNotNull(plainBody);
        assertEquals(patientId, String.valueOf(plainBody.get("id")),
                "the plain-token read must reach the created synthetic record");

        HttpHeaders proposedContext = new HttpHeaders();
        proposedContext.setBearerAuth(token);
        proposedContext.set("X-Acting-Assignment-Id", "00000000-0000-0000-0000-00000000000a");
        proposedContext.set("X-Acting-Branch-Id", "00000000-0000-0000-0000-00000000000b");
        proposedContext.set("X-Acting-Role", "NURSE");
        ResponseEntity<Map<String, Object>> decorated = rest.exchange(
                "/api/patients/" + patientId, HttpMethod.GET, new HttpEntity<>(proposedContext), MAP);
        assertEquals(HttpStatus.OK, decorated.getStatusCode(),
                "proposed context headers must not break or scope the read today");
        assertEquals(plainBody, decorated.getBody(),
                "arbitrary context headers have no scoping effect on today's global reads");
    }

    // ------------------------------------------------------------------
    // 4. Departments are branchless raw entity records.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 2 will intentionally change it): department
     * create returns the raw JPA entity — domain fields plus persistence
     * metadata — with no DTO allowlist and no branch/organization
     * relationship anywhere in the response; the row lands in the global
     * unscoped list.
     */
    @Test
    void departmentsAreBranchlessRawEntityRecordsToday() {
        String token = login(ADMIN);
        Map<String, Object> payload = departmentPayload("raw");
        ResponseEntity<Map<String, Object>> created = postJson("/api/departments", token, payload);
        assertTrue(created.getStatusCode().is2xxSuccessful(), "ADMIN must be able to create a department today");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(Set.of("id", "createdAt", "updatedAt", "version", "code", "name", "specialty", "location"),
                body.keySet(),
                "today's department response is the raw entity: domain fields plus persistence metadata");
        assertEquals(payload.get("code"), body.get("code"));
        assertTrue(body.keySet().stream()
                        .noneMatch(key -> key.toLowerCase().contains("branch") || key.toLowerCase().contains("organization")),
                "no branch/organization relationship may exist on today's department record");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/departments", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> rows = list.getBody();
        assertNotNull(rows, "the department list must carry a body");
        assertTrue(rows.stream().anyMatch(row -> body.get("id").equals(row.get("id"))),
                "the created department must appear in the global unscoped list");
    }

    // ------------------------------------------------------------------
    // 5. Beds accept free-form branchless occupancy.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 6 will intentionally change it): POST /api/beds
     * accepts an arbitrary nonblank occupancy status and a synthetic
     * nonexistent patientId without any reference verification, returns the
     * raw entity (no DTO, no branchId), the row appears in the global list,
     * and the existing CREATE audit evidence remains observable through the
     * plain audit list without depending on event ordering.
     */
    @Test
    void bedsAcceptFreeFormBranchlessOccupancyWithAnUnverifiedPatientReferenceToday() {
        String token = login(ADMIN);
        Map<String, Object> payload = bedPayload("ghost");
        ResponseEntity<Map<String, Object>> created = postJson("/api/beds", token, payload);
        assertTrue(created.getStatusCode().is2xxSuccessful(),
                "today's raw bed CRUD must accept the free-form request");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(Set.of("id", "createdAt", "updatedAt", "version", "ward", "room", "bedNumber",
                        "occupancyStatus", "patientId"),
                body.keySet(),
                "today's bed response is the raw entity with free-form string occupancy fields");
        assertEquals(payload.get("occupancyStatus"), body.get("occupancyStatus"),
                "any nonblank occupancy status string must be accepted today");
        assertEquals(payload.get("patientId"), body.get("patientId"),
                "an unverified nonexistent patientId must be stored as-is today");
        assertFalse(body.containsKey("branchId"), "no branch ownership exists on today's bed record");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/beds", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> rows = list.getBody();
        assertNotNull(rows, "the bed list must carry a body");
        assertTrue(rows.stream().anyMatch(row -> body.get("id").equals(row.get("id"))),
                "the created bed must appear in the global unscoped list");

        ResponseEntity<List<Map<String, Object>>> auditResponse = getList("/api/audit", token);
        assertEquals(HttpStatus.OK, auditResponse.getStatusCode());
        List<Map<String, Object>> events = auditResponse.getBody();
        assertNotNull(events, "the audit list must carry a body");
        assertTrue(events.stream().anyMatch(event ->
                        "CREATE".equals(event.get("action"))
                                && "Bed".equals(event.get("resourceType"))
                                && String.valueOf(body.get("id")).equals(event.get("resourceId"))),
                "the bed create must leave observable CREATE audit evidence for its resource id");
    }

    // ------------------------------------------------------------------
    // 6. Appointments accept overlapping times for one professional.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 9 will intentionally change it): two
     * appointments for the same verified professional at exactly the same
     * timestamp both succeed today — no availability model and no overlap
     * rejection exists. Proven through observable HTTP records and distinct
     * ids, never through repository call counts.
     */
    @Test
    void appointmentsAcceptOverlappingTimesForTheSameProfessionalToday() {
        String token = login(ADMIN);
        String patientId = createVerifiedPatientId(token, "ovl");
        String professionalId = createVerifiedStaffId(token, "ovl");
        Map<String, Object> payload = appointmentPayload(patientId, professionalId);

        ResponseEntity<Map<String, Object>> firstResponse = postJson("/api/appointments", token, payload);
        ResponseEntity<Map<String, Object>> secondResponse = postJson("/api/appointments", token, payload);
        assertTrue(firstResponse.getStatusCode().is2xxSuccessful(), "the first overlapping create must succeed today");
        assertTrue(secondResponse.getStatusCode().is2xxSuccessful(),
                "the second identical-slot create must also succeed today — no overlap check exists");
        Map<String, Object> first = firstResponse.getBody();
        Map<String, Object> second = secondResponse.getBody();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.get("id"), second.get("id"),
                "the two overlapping appointments must be two distinct persisted records");
        assertEquals(professionalId, String.valueOf(first.get("professionalId")));
        assertEquals(professionalId, String.valueOf(second.get("professionalId")));
        assertNotNull(first.get("scheduledAt"));
        assertEquals(first.get("scheduledAt"), second.get("scheduledAt"),
                "both appointments must carry the exact same scheduled time");
    }

    // ------------------------------------------------------------------
    // 7. Dashboard is whole-table and branchless.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 10 will intentionally change it): /api/dashboard
     * exposes exactly the eleven flat numeric keys — no branch, network, or
     * organization structure — and newly created synthetic rows move the
     * whole-table totals regardless of any (nonexistent) branch notion.
     * Increments are asserted relative to a baseline read so the test stays
     * independent of suite ordering.
     */
    @Test
    void dashboardIsAWholeTableBranchlessFlatCountMapToday() {
        String token = login(ADMIN);
        Map<String, Object> before = readDashboard(token);
        assertEquals(DASHBOARD_KEYS, before.keySet(),
                "today's dashboard is exactly the eleven flat keys with no branch/network keys");

        String patientId = createVerifiedPatientId(token, "dash");
        String professionalId = createVerifiedStaffId(token, "dash");
        postJson("/api/appointments", token, appointmentPayload(patientId, professionalId));

        Map<String, Object> after = readDashboard(token);
        assertEquals(DASHBOARD_KEYS, after.keySet(), "the dashboard must keep the same flat key set");
        assertEquals(count(before, "patients") + 1, count(after, "patients"),
                "the whole-table patient total must include the newly created synthetic row");
        assertEquals(count(before, "appointments") + 1, count(after, "appointments"),
                "the whole-table appointment total must include the newly created synthetic row");
    }

    // ------------------------------------------------------------------
    // 8. Audit events lack organizational context.
    // ------------------------------------------------------------------

    /**
     * Current baseline (Task 11 will intentionally change it): a successful
     * mutation emits one event carrying only the actor/action/resource/
     * details/time contract plus persistence metadata — no assignment, role,
     * organization, branch, department, or correlation fields. Existing ids
     * and timestamps are never asserted literally; the event is located by
     * its resource id, not by ordering.
     */
    @Test
    void auditEventsCarryNoOrganizationalContextToday() {
        String token = login(ADMIN);
        Map<String, Object> payload = departmentPayload("audit");
        ResponseEntity<Map<String, Object>> created = postJson("/api/departments", token, payload);
        assertTrue(created.getStatusCode().is2xxSuccessful(), "the audited create must succeed");
        String resourceId = String.valueOf(created.getBody().get("id"));

        ResponseEntity<List<Map<String, Object>>> auditResponse = getList("/api/audit", token);
        assertEquals(HttpStatus.OK, auditResponse.getStatusCode());
        List<Map<String, Object>> events = auditResponse.getBody();
        assertNotNull(events, "the audit list must carry a body");
        Map<String, Object> event = events.stream()
                .filter(candidate -> "Department".equals(candidate.get("resourceType"))
                        && resourceId.equals(candidate.get("resourceId")))
                .findFirst()
                .orElse(null);
        assertNotNull(event, "the department create must have produced one observable audit event");
        assertEquals(Set.of("id", "createdAt", "updatedAt", "version", "actor", "action",
                        "resourceType", "resourceId", "details", "occurredAt"),
                event.keySet(),
                "today's audit event carries only actor/action/resource/details/time plus persistence metadata");
        assertEquals(ADMIN, event.get("actor"));
        assertEquals("CREATE", event.get("action"));
        assertEquals("Department", event.get("resourceType"));
        assertNotNull(event.get("occurredAt"), "the event must carry its occurred-at time");
        assertTrue(event.keySet().stream().noneMatch(key -> {
            String lowered = key.toLowerCase();
            return lowered.contains("assignment") || lowered.contains("branch")
                    || lowered.contains("organization") || lowered.contains("department")
                    || lowered.contains("role") || lowered.contains("correlation");
        }), "no organizational-context field may exist on today's audit event");
    }

    // ------------------------------------------------------------------
    // HTTP and synthetic-data helpers
    // ------------------------------------------------------------------

    private String login(String username) {
        ResponseEntity<Map<String, Object>> response = postJson("/api/auth/login", null, Map.of(
                "username", username,
                "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the synthetic account must log in");
        assertNotNull(response.getBody());
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<Map<String, Object>> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), LIST);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, String token, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
    }

    private Map<String, Object> readDashboard(String token) {
        ResponseEntity<Map<String, Object>> response = getJson("/api/dashboard", token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the dashboard response must carry a body");
        return body;
    }

    private long count(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }

    /** Synthetic CreatePatientRequest body, unique per tag within this run. */
    private Map<String, Object> patientCreatePayload(String tag) {
        return Map.ofEntries(
                Map.entry("medicalRecordNumber", "MRN-MB-" + suffix + "-" + tag),
                Map.entry("fullName", "Synthetic MultiBranch Patient " + suffix + " " + tag),
                Map.entry("dateOfBirth", "1991-02-03"),
                Map.entry("sex", "unspecified"),
                Map.entry("phone", "+15550003333"),
                Map.entry("email", "mb-" + tag + "-" + suffix + "@synthetic.test"),
                Map.entry("nationalId", "NID-MB-" + suffix + "-" + tag),
                Map.entry("address", "3 MultiBranch Avenue"));
    }

    /** Synthetic staff create body over obviously fake directory values. */
    private Map<String, Object> staffCreatePayload(String tag) {
        return Map.of(
                "employeeCode", "EMP-MB-" + suffix + "-" + tag,
                "fullName", "Dr. Synthetic MultiBranch " + suffix + " " + tag,
                "profession", "cardiology",
                "licenseNumber", "LIC-MB-" + suffix + "-" + tag,
                "department", "internal medicine");
    }

    /** Synthetic raw department body with unique display values. */
    private Map<String, Object> departmentPayload(String tag) {
        return Map.of(
                "code", "DEP-MB-" + suffix + "-" + tag,
                "name", "Synthetic MultiBranch Department " + suffix + " " + tag,
                "specialty", "general",
                "location", "4 MultiBranch Avenue");
    }

    /** Synthetic raw bed body: free-form status plus a nonexistent patient reference. */
    private Map<String, Object> bedPayload(String tag) {
        return Map.of(
                "ward", "WARD-MB-" + suffix,
                "room", "ROOM-MB-" + suffix + "-" + tag,
                "bedNumber", "BED-MB-" + suffix + "-" + tag,
                "occupancyStatus", "ARBITRARY-STATE-" + tag,
                "patientId", UUID.randomUUID().toString());
    }

    /** Synthetic CreateAppointmentRequest body over verified references. */
    private Map<String, Object> appointmentPayload(String patientId, String professionalId) {
        return Map.of(
                "patientId", patientId,
                "professionalId", professionalId,
                "scheduledAt", "2036-07-08T10:30:00",
                "type", "consultation",
                "status", "scheduled");
    }

    private String createVerifiedPatientId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/patients", token, patientCreatePayload(tag));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "synthetic patient creation must succeed");
        assertNotNull(created.getBody());
        return String.valueOf(created.getBody().get("id"));
    }

    private String createVerifiedStaffId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/staff", token, staffCreatePayload(tag));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "synthetic staff creation must succeed");
        assertNotNull(created.getBody());
        return String.valueOf(created.getBody().get("id"));
    }
}

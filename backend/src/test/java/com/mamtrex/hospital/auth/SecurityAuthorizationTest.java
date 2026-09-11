package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused authorization integration test. Runs against an isolated in-memory H2
 * database (never the production file store) with disposable runtime secrets.
 * Verifies the endpoint-family role policy: anonymous requests are rejected with
 * 401, ADMIN has full access, and a NURSE is allowed clinical/dashboard endpoints
 * but forbidden from audit.
 *
 * Task 9 (docs/plan1.md) aligns the write-endpoint half of the documented role
 * matrix with the frontend capability hints (frontend/src/authorization.js):
 * patient create/update and appointment create are ADMIN/RECEPTIONIST-only on
 * the server (DOCTOR/NURSE receive 403), and the staff directory read opens to
 * RECEPTIONIST for scheduling while staff writes stay ADMIN/HR-only. These
 * tests assert that enforced backend policy (packet
 * MEDICORE-RBAC-ENFORCEMENT-TASK9-019) so no implemented write action depends
 * solely on frontend hiding, satisfying the Task 9 completion gate.
 *
 * Task 6 (docs/plan2.md) completes the matrix across the care-operations
 * surface: admissions and emergency visits admit exactly ADMIN, DOCTOR,
 * NURSE, and RECEPTIONIST on every method (reads, creates, and status
 * transitions), invoices admit exactly ADMIN and BILLING, the dashboard
 * stays reachable for every authenticated role, audit stays ADMIN-only,
 * and anonymous callers hit 401 on every named surface. Denied roles are
 * refused with 403 and their refused writes/transitions must persist
 * nothing and change no state. These are direct HTTP proofs of the enforced
 * SecurityConfig family rules (never retested lifecycle validation, which
 * CareOperationsApiTest owns) so the frontend capability hints in
 * frontend/src/authorization.js mirror, and never replace, server
 * enforcement. Deny-by-default is preserved: a representative role outside
 * every named family (LAB_TECH) gets only the dashboard.
 *
 * Plan 3 Task 3 (docs/plan3.md, packet MEDICORE-PLAN3-TASK3-050) replaces the
 * global role model with acting assignments and a branch-bound acting context:
 * every synthetic account logs in through an explicit enabled assignment, the
 * login response carries exactly the six-key allowlist with only the selected
 * assignment role, POST /api/auth/context issues replacement tokens only for
 * assignments owned by the subject, and the JWT filter re-derives exactly one
 * authority from server assignment state on every request. The Task 1 baseline
 * pins for the global shape were intentionally replaced by these Task 3 pins;
 * the care-operations and Task 9 matrices above are neither weakened nor
 * duplicated.
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
    private static final String DOCTOR = "doctor1";
    private static final String RECEPTIONIST = "receptionist1";
    /**
     * BILLING is deliberately isolated to the invoice family: outside every
     * patients/appointments/admissions/emergency-visits path rule.
     */
    private static final String DENIED = "billing1";
    /** Representative deny-by-default role outside every named family. */
    private static final String LAB = "labtech1";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    @Autowired
    DepartmentRepository departments;

    /** Destructive branch-state manipulation only (no public deactivation endpoint exists). */
    @Autowired
    JdbcTemplate jdbc;

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private static final String TEST_ORG_CODE = "AUTHZ-ORG";
    private static final String DEFAULT_BRANCH_CODE = "AUTHZ-BR-DEFAULT";
    private static final String OTHER_BRANCH_CODE = "AUTHZ-BR-OTHER";
    private static final String INVALID_CREDENTIALS_BODY = "Invalid username or password.";
    private static final Set<String> LOGIN_KEYS = Set.of(
            "accessToken", "tokenType", "username", "roles", "assignments", "actingContext");
    private static final Set<String> ASSIGNMENT_VIEW_KEYS = Set.of(
            "id", "role", "scope", "organizationId", "organizationLabel",
            "branchId", "branchLabel", "departmentId", "departmentLabel", "enabled");
    private static final Set<String> ACTING_CONTEXT_KEYS = Set.of(
            "username", "assignmentId", "role", "scope", "organizationId", "branchId", "departmentId");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    /**
     * Task 3 seeding: every synthetic account logs in through an explicit
     * enabled acting assignment — a deterministic active branch for branch
     * scopes and an organization scope for ADMIN. No compatibility fallback
     * keeps the old global-role logins alive.
     */
    @BeforeEach
    void seedTestAccounts() {
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Synthetic Authorization Hospital")));
        Branch defaultBranch = ensureBranch(org, DEFAULT_BRANCH_CODE, "1 Authorization Way");
        ensureBranch(org, OTHER_BRANCH_CODE, "2 Authorization Way");
        seedAccountWithAssignment(ADMIN, Role.ADMIN, AssignmentScope.ORGANIZATION, org, null);
        seedAccountWithAssignment(NURSE, Role.NURSE, AssignmentScope.BRANCH, org, defaultBranch);
        seedAccountWithAssignment(DOCTOR, Role.DOCTOR, AssignmentScope.BRANCH, org, defaultBranch);
        seedAccountWithAssignment(RECEPTIONIST, Role.RECEPTIONIST, AssignmentScope.BRANCH, org, defaultBranch);
        seedAccountWithAssignment(DENIED, Role.BILLING, AssignmentScope.BRANCH, org, defaultBranch);
        seedAccountWithAssignment(LAB, Role.LAB_TECH, AssignmentScope.BRANCH, org, defaultBranch);
    }

    private Branch ensureBranch(HospitalOrganization org, String code, String location) {
        return branches.findByOrganizationIdAndCode(org.getId(), code).orElseGet(() ->
                branches.save(new Branch(org, code, "Synthetic Branch " + code, location)));
    }

    private void seedAccountWithAssignment(String username, Role role, AssignmentScope scope,
                                           HospitalOrganization org, Branch branch) {
        if (accounts.findByUsername(username).isEmpty()) {
            accounts.save(new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(role)));
        }
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        boolean present = switch (scope) {
            case ORGANIZATION -> assignments
                    .findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(account.getId(), role, scope)
                    .isPresent();
            case BRANCH -> assignments
                    .findByAccountIdAndRoleAndScopeAndBranchId(account.getId(), role, scope, branch.getId())
                    .isPresent();
            case DEPARTMENT -> false;
        };
        if (!present) {
            assignments.save(switch (scope) {
                case ORGANIZATION -> ActingAssignment.organization(account, org, role);
                case BRANCH -> ActingAssignment.branch(account, org, role, branch);
                case DEPARTMENT -> throw new IllegalArgumentException(
                        "Account seeding supports organization/branch scopes only");
            });
        }
    }

    /** Dedicated disposable account with exactly one enabled assignment, for destructive tests. */
    private UserAccount newDedicatedAccount(String tag, Role role, AssignmentScope scope,
                                            HospitalOrganization org, Branch branch, Department department) {
        UserAccount account = accounts.save(new UserAccount(tag + "-" + suffix,
                encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(role)));
        assignments.save(switch (scope) {
            case ORGANIZATION -> ActingAssignment.organization(account, org, role);
            case BRANCH -> ActingAssignment.branch(account, org, role, branch);
            case DEPARTMENT -> ActingAssignment.department(account, org, role, department);
        });
        return account;
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

    private ResponseEntity<Map<String, Object>> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, String token, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, String token, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(payload, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** Synthetic CreatePatientRequest body, unique per tag within this run. */
    private Map<String, Object> patientCreatePayload(String tag) {
        return Map.ofEntries(
                Map.entry("medicalRecordNumber", "MRN-" + suffix + "-" + tag),
                Map.entry("fullName", "Synthetic Matrix Patient " + suffix + " " + tag),
                Map.entry("dateOfBirth", "1990-01-02"),
                Map.entry("sex", "unspecified"),
                Map.entry("phone", "+15550001111"),
                Map.entry("email", "matrix-" + tag + "-" + suffix + "@synthetic.test"),
                Map.entry("nationalId", "NID-" + suffix + "-" + tag),
                Map.entry("address", "1 Matrix Avenue"));
    }

    /** Synthetic UpdatePatientRequest body: exactly the four mutable fields. */
    private Map<String, Object> patientUpdatePayload(String tag) {
        return Map.of(
                "fullName", "Updated Matrix Patient " + suffix + " " + tag,
                "phone", "+15550002222",
                "email", "updated-" + tag + "-" + suffix + "@synthetic.test",
                "address", "2 Matrix Avenue " + tag);
    }

    /** Synthetic CreateAppointmentRequest body over verified references. */
    private Map<String, Object> appointmentPayload(String patientId, String professionalId, int hourSlot) {
        return Map.of(
                "patientId", patientId,
                "professionalId", professionalId,
                "scheduledAt", String.format("2033-05-06T%02d:30:00", hourSlot),
                "type", "consultation",
                "status", "scheduled");
    }

    private String createVerifiedPatientId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/patients", token, patientCreatePayload(tag));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "synthetic patient creation must succeed");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        return String.valueOf(body.get("id"));
    }

    private String createVerifiedStaffId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/staff", token, Map.of(
                "employeeCode", "EMP-" + suffix + "-" + tag,
                "fullName", "Dr. Synthetic Matrix " + suffix + " " + tag,
                "profession", "cardiology",
                "licenseNumber", "LIC-" + suffix + "-" + tag,
                "department", "internal medicine"));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "synthetic staff creation must succeed");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        return String.valueOf(body.get("id"));
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

    /**
     * Task 9 matrix — POST /api/patients (the "New patient" action).
     * ENFORCED POLICY (matches frontend authorization.js create:
     * ADMIN/RECEPTIONIST): ADMIN and RECEPTIONIST may create patients,
     * while DOCTOR and NURSE — admitted to /api/patients/** reads by the
     * path rule — are refused with 403 on the write, and a refused attempt
     * must not persist a record. Every other role stays deny-by-default.
     */
    @Test
    void patientCreateEnforcesAdminReceptionistOnlyWrites() {
        for (String username : List.of(ADMIN, RECEPTIONIST)) {
            ResponseEntity<Map<String, Object>> created =
                    postJson("/api/patients", login(username), patientCreatePayload(username));
            assertTrue(created.getStatusCode().is2xxSuccessful(),
                    username + " holds the patient-create role and must be admitted");
        }
        String lastDeniedFullName = null;
        for (String username : List.of(DOCTOR, NURSE)) {
            ResponseEntity<Map<String, Object>> created =
                    postJson("/api/patients", login(username), patientCreatePayload(username));
            assertEquals(HttpStatus.FORBIDDEN, created.getStatusCode(),
                    username + " must be refused the patient-create write with 403");
            lastDeniedFullName = "Synthetic Matrix Patient " + suffix + " " + username;
        }
        assertEquals(HttpStatus.FORBIDDEN,
                postJson("/api/patients", login(DENIED), patientCreatePayload(DENIED)).getStatusCode(),
                "a role outside the four-role path rule must be denied with 403");
        ResponseEntity<List<Map<String, Object>>> leaked =
                getList("/api/patients?q=" + lastDeniedFullName, login(ADMIN));
        assertEquals(HttpStatus.OK, leaked.getStatusCode());
        List<Map<String, Object>> leakedBody = leaked.getBody();
        assertNotNull(leakedBody, "patient search must carry a body");
        assertTrue(leakedBody.isEmpty(),
                "a 403 patient-create attempt must not persist a record");
    }

    /**
     * Task 9 matrix — PUT /api/patients/{id} (the "Edit record" action).
     * ENFORCED POLICY (matches frontend authorization.js update:
     * ADMIN/RECEPTIONIST, with DOCTOR kept on a read-only form view):
     * permitted roles persist their write, DOCTOR/NURSE are refused with
     * 403, and a refused attempt must leave the record unchanged.
     */
    @Test
    void patientUpdateEnforcesAdminReceptionistOnlyWrites() {
        String adminToken = login(ADMIN);
        String patientId = createVerifiedPatientId(adminToken, "upd");
        String path = "/api/patients/" + patientId;
        String lastWrittenName = null;
        for (String username : List.of(ADMIN, RECEPTIONIST)) {
            Map<String, Object> payload = patientUpdatePayload(username);
            ResponseEntity<Map<String, Object>> updated = putJson(path, login(username), payload);
            assertTrue(updated.getStatusCode().is2xxSuccessful(),
                    username + " holds the patient-edit role and must be admitted");
            lastWrittenName = (String) payload.get("fullName");
            assertEquals(lastWrittenName, updated.getBody() != null ? updated.getBody().get("fullName") : null,
                    "the permitted update must actually persist the new value");
        }
        for (String username : List.of(DOCTOR, NURSE)) {
            assertEquals(HttpStatus.FORBIDDEN,
                    putJson(path, login(username), patientUpdatePayload(username)).getStatusCode(),
                    username + " must be refused the patient-edit write with 403");
        }
        assertEquals(HttpStatus.FORBIDDEN,
                putJson(path, login(DENIED), patientUpdatePayload(DENIED)).getStatusCode(),
                "a role outside the four-role path rule must be denied with 403");
        ResponseEntity<Map<String, Object>> after = getJson(path, adminToken);
        assertTrue(after.getStatusCode().is2xxSuccessful());
        assertEquals(lastWrittenName, after.getBody() != null ? after.getBody().get("fullName") : null,
                "the denied update attempts must not have mutated the record");
    }

    /**
     * Task 9 matrix — POST /api/appointments (the "Schedule appointment"
     * action). ENFORCED POLICY (matches frontend authorization.js create:
     * ADMIN/RECEPTIONIST): DOCTOR/NURSE are refused with 403 and no
     * appointment is persisted for their refused attempts. Server
     * enforcement stays authoritative and deny-by-default for every other
     * role.
     */
    @Test
    void appointmentCreateEnforcesAdminReceptionistOnlyWrites() {
        String adminToken = login(ADMIN);
        String patientId = createVerifiedPatientId(adminToken, "appt");
        String professionalId = createVerifiedStaffId(adminToken, "appt");
        int slot = 8;
        for (String username : List.of(ADMIN, RECEPTIONIST)) {
            ResponseEntity<Map<String, Object>> created = postJson("/api/appointments", login(username),
                    appointmentPayload(patientId, professionalId, slot++));
            assertTrue(created.getStatusCode().is2xxSuccessful(),
                    username + " holds the scheduling role and must be admitted");
        }
        List<String> refusedSlots = new ArrayList<>();
        for (String username : List.of(DOCTOR, NURSE)) {
            int refusedSlot = slot++;
            assertEquals(HttpStatus.FORBIDDEN,
                    postJson("/api/appointments", login(username),
                            appointmentPayload(patientId, professionalId, refusedSlot)).getStatusCode(),
                    username + " must be refused the scheduling write with 403");
            refusedSlots.add(String.format("2033-05-06T%02d:30", refusedSlot));
        }
        assertEquals(HttpStatus.FORBIDDEN,
                postJson("/api/appointments", login(DENIED),
                        appointmentPayload(patientId, professionalId, slot)).getStatusCode(),
                "a role outside the four-role path rule must be denied with 403");
        ResponseEntity<List<Map<String, Object>>> list = getList("/api/appointments", adminToken);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "appointment list must carry a body");
        for (String refusedSlot : refusedSlots) {
            assertTrue(listBody.stream().noneMatch(a -> refusedSlot.equals(a.get("scheduledAt"))),
                    "a 403 scheduling attempt must not persist an appointment at " + refusedSlot);
        }
    }

    /**
     * ENFORCED POLICY: GET /api/staff/** additionally allows RECEPTIONIST —
     * the primary scheduling role needs the professional directory — while
     * staff writes stay ADMIN/HR-only and every other read-only role stays
     * deny-by-default.
     */
    @Test
    void staffDirectoryAllowsReceptionistReadWhileWritesStayAdminHrOnly() {
        // The staff directory answers with a JSON array, so the String-body
        // helper is used here; only the status is under assertion.
        assertEquals(HttpStatus.OK, get("/api/staff", login(ADMIN)).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/staff", login(RECEPTIONIST)).getStatusCode(),
                "RECEPTIONIST scheduling needs the professional directory read");
        assertEquals(HttpStatus.FORBIDDEN, get("/api/staff", login(NURSE)).getStatusCode(),
                "only ADMIN/HR/RECEPTIONIST hold the staff directory read");
        assertEquals(HttpStatus.FORBIDDEN, postJson("/api/staff", login(RECEPTIONIST), Map.of(
                "employeeCode", "EMP-" + suffix + "-recep",
                "fullName", "Dr. Refused Receptionist Write " + suffix,
                "profession", "cardiology",
                "licenseNumber", "LIC-" + suffix + "-recep",
                "department", "internal medicine")).getStatusCode(),
                "staff writes must stay ADMIN/HR-only");
    }

    // ------------------------------------------------------------------
    // Task 6 matrix (docs/plan2.md) — care-operations role families,
    // proved by direct HTTP against the enforced SecurityConfig rules.
    // ------------------------------------------------------------------

    /** Synthetic CreateAdmissionRequest body over the verified patient. */
    private Map<String, Object> admissionCreatePayload(String tag, String patientId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("admittedAt", "2034-03-04T08:15:00");
        payload.put("reason", "synthetic matrix admission " + suffix + " " + tag);
        return payload;
    }

    /** Synthetic CreateEmergencyVisitRequest body; the triage label is the neutral demo value "3". */
    private Map<String, Object> emergencyCreatePayload(String tag, String patientId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("arrivalAt", "2034-03-04T09:15:00");
        payload.put("triageLevel", "3");
        payload.put("chiefComplaint", "synthetic matrix complaint " + suffix + " " + tag);
        return payload;
    }

    /** Synthetic CreateInvoiceRequest body — a FINANCIAL SIMULATION with demo amount/currency only. */
    private Map<String, Object> invoiceCreatePayload(String tag, String patientId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("invoiceNumber", "INV-MATRIX-" + suffix + "-" + tag);
        payload.put("amount", new BigDecimal("25.50"));
        payload.put("currency", "USD");
        return payload;
    }

    /** Numeric aggregate from the authenticated dashboard, e.g. "admissions" or "invoicesDraft". */
    private long dashboardCount(String key, String token) {
        ResponseEntity<Map<String, Object>> dashboard = getJson("/api/dashboard", token);
        assertEquals(HttpStatus.OK, dashboard.getStatusCode());
        Object value = dashboard.getBody() != null ? dashboard.getBody().get(key) : null;
        assertInstanceOf(Number.class, value, "dashboard key '" + key + "' must be a numeric count");
        return ((Number) value).longValue();
    }

    private String requireBodyId(ResponseEntity<Map<String, Object>> response, String what) {
        assertTrue(response.getStatusCode().is2xxSuccessful(), what + " must be admitted");
        Object id = response.getBody() != null ? response.getBody().get("id") : null;
        assertNotNull(id, what + " response must carry its UUID identity");
        return String.valueOf(id);
    }

    /**
     * Task 6 matrix — anonymous callers hit the 401 boundary on every named
     * surface: dashboard, patients, appointments, admissions, emergency
     * visits, invoices, and audit. Authentication is refused before any
     * role question is asked.
     */
    @Test
    void anonymousIsUnauthorizedOnEveryPlan2NamedSurface() {
        for (String path : List.of("/api/dashboard", "/api/patients", "/api/appointments",
                "/api/admissions", "/api/emergency-visits", "/api/invoices", "/api/audit")) {
            assertEquals(HttpStatus.UNAUTHORIZED, get(path, null).getStatusCode(),
                    "anonymous GET " + path + " must stay on the 401 boundary");
        }
    }

    /**
     * Task 6 matrix — reads. Admissions and emergency visits admit exactly
     * ADMIN, DOCTOR, NURSE, RECEPTIONIST; invoices admit exactly ADMIN and
     * BILLING; the dashboard stays reachable for every authenticated role;
     * audit stays ADMIN-only; and a representative deny-by-default role
     * (LAB_TECH) is refused every family read with 403.
     */
    @Test
    void careOperationReadsAdmitExactlyTheDocumentedRoleFamilies() {
        for (String path : List.of("/api/admissions", "/api/emergency-visits")) {
            for (String username : List.of(ADMIN, DOCTOR, NURSE, RECEPTIONIST)) {
                assertEquals(HttpStatus.OK, get(path, login(username)).getStatusCode(),
                        username + " holds the " + path + " family read and must be admitted");
            }
            assertEquals(HttpStatus.FORBIDDEN, get(path, login(DENIED)).getStatusCode(),
                    "BILLING must be refused the " + path + " read with 403");
        }
        for (String username : List.of(ADMIN, DENIED)) {
            assertEquals(HttpStatus.OK, get("/api/invoices", login(username)).getStatusCode(),
                    username + " holds the invoice-family read and must be admitted");
        }
        for (String username : List.of(DOCTOR, NURSE, RECEPTIONIST)) {
            assertEquals(HttpStatus.FORBIDDEN, get("/api/invoices", login(username)).getStatusCode(),
                    username + " must be refused the invoice read with 403");
        }
        for (String username : List.of(ADMIN, DOCTOR, NURSE, RECEPTIONIST, DENIED, LAB)) {
            assertEquals(HttpStatus.OK, get("/api/dashboard", login(username)).getStatusCode(),
                    "the dashboard must stay reachable for every authenticated role including " + username);
        }
        assertEquals(HttpStatus.OK, get("/api/audit", login(ADMIN)).getStatusCode());
        for (String username : List.of(DOCTOR, NURSE, RECEPTIONIST, DENIED, LAB)) {
            assertEquals(HttpStatus.FORBIDDEN, get("/api/audit", login(username)).getStatusCode(),
                    username + " must be refused the audit read with 403");
        }
        for (String path : List.of("/api/patients", "/api/admissions", "/api/emergency-visits",
                "/api/invoices")) {
            assertEquals(HttpStatus.FORBIDDEN, get(path, login(LAB)).getStatusCode(),
                    "a deny-by-default role must be refused " + path + " with 403");
        }
    }

    /**
     * Task 6 matrix — admission writes and transitions. RECEPTIONIST
     * registers, DOCTOR registers, and NURSE discharges (the family rule
     * keeps all four roles admitted on every method); BILLING is refused
     * with 403, its refused create persists no record, and its refused
     * discharge leaves the persisted status untouched.
     */
    @Test
    void admissionWritesAdmitTheFourRolesWhileBillingPersistsNothing() {
        String adminToken = login(ADMIN);
        String patientId = createVerifiedPatientId(adminToken, "adm");

        String receptionistAdmissionId = requireBodyId(
                postJson("/api/admissions", login(RECEPTIONIST), admissionCreatePayload("recep", patientId)),
                "the RECEPTIONIST admission create");
        String doctorAdmissionId = requireBodyId(
                postJson("/api/admissions", login(DOCTOR), admissionCreatePayload("doctor", patientId)),
                "the DOCTOR admission create");

        ResponseEntity<Map<String, Object>> discharged = putJson(
                "/api/admissions/" + receptionistAdmissionId + "/status", login(NURSE),
                Map.of("status", "DISCHARGED"));
        assertTrue(discharged.getStatusCode().is2xxSuccessful(),
                "NURSE holds the admission transition and must be admitted");
        assertEquals("DISCHARGED", discharged.getBody() != null ? discharged.getBody().get("status") : null,
                "the admitted discharge must actually persist the server-owned status");

        long totalBefore = dashboardCount("admissions", adminToken);
        long openBefore = dashboardCount("openAdmissions", adminToken);
        assertEquals(HttpStatus.FORBIDDEN,
                postJson("/api/admissions", login(DENIED), admissionCreatePayload("billing", patientId))
                        .getStatusCode(),
                "BILLING must be refused the admission create with 403");
        assertEquals(totalBefore, dashboardCount("admissions", adminToken),
                "a 403 admission-create attempt must not persist a record");
        assertEquals(openBefore, dashboardCount("openAdmissions", adminToken),
                "a 403 admission-create attempt must not change the open-admission aggregate");

        assertEquals(HttpStatus.FORBIDDEN,
                putJson("/api/admissions/" + doctorAdmissionId + "/status", login(DENIED),
                        Map.of("status", "DISCHARGED")).getStatusCode(),
                "BILLING must be refused the admission discharge with 403");
        ResponseEntity<Map<String, Object>> after = getJson("/api/admissions/" + doctorAdmissionId, adminToken);
        assertEquals("ADMITTED", after.getBody() != null ? after.getBody().get("status") : null,
                "a 403 discharge attempt must leave the persisted status untouched");
    }

    /**
     * Task 6 matrix — emergency-visit writes and transitions. DOCTOR
     * registers and moves the visit into treatment, NURSE registers a
     * second visit; BILLING is refused with 403, its refused create
     * persists no record, and its refused transition leaves the persisted
     * status untouched. Triage stays the neutral 1-5 demo label.
     */
    @Test
    void emergencyWritesAdmitTheFourRolesWhileBillingPersistsNothing() {
        String adminToken = login(ADMIN);
        String patientId = createVerifiedPatientId(adminToken, "erg");

        String doctorVisitId = requireBodyId(
                postJson("/api/emergency-visits", login(DOCTOR), emergencyCreatePayload("doctor", patientId)),
                "the DOCTOR emergency-visit create");
        String nurseVisitId = requireBodyId(
                postJson("/api/emergency-visits", login(NURSE), emergencyCreatePayload("nurse", patientId)),
                "the NURSE emergency-visit create");

        ResponseEntity<Map<String, Object>> moved = putJson(
                "/api/emergency-visits/" + doctorVisitId + "/status", login(RECEPTIONIST),
                Map.of("status", "IN_TREATMENT"));
        assertTrue(moved.getStatusCode().is2xxSuccessful(),
                "RECEPTIONIST holds the emergency-visit transition and must be admitted");
        assertEquals("IN_TREATMENT", moved.getBody() != null ? moved.getBody().get("status") : null,
                "the admitted transition must actually persist the server-owned status");

        long totalBefore = dashboardCount("emergencyVisits", adminToken);
        long activeBefore = dashboardCount("activeEmergencyVisits", adminToken);
        assertEquals(HttpStatus.FORBIDDEN,
                postJson("/api/emergency-visits", login(DENIED), emergencyCreatePayload("billing", patientId))
                        .getStatusCode(),
                "BILLING must be refused the emergency-visit create with 403");
        assertEquals(totalBefore, dashboardCount("emergencyVisits", adminToken),
                "a 403 emergency-visit-create attempt must not persist a record");
        assertEquals(activeBefore, dashboardCount("activeEmergencyVisits", adminToken),
                "a 403 emergency-visit-create attempt must not change the active-visit aggregate");

        assertEquals(HttpStatus.FORBIDDEN,
                putJson("/api/emergency-visits/" + nurseVisitId + "/status", login(DENIED),
                        Map.of("status", "IN_TREATMENT")).getStatusCode(),
                "BILLING must be refused the emergency-visit transition with 403");
        ResponseEntity<Map<String, Object>> after = getJson("/api/emergency-visits/" + nurseVisitId, adminToken);
        assertEquals("WAITING", after.getBody() != null ? after.getBody().get("status") : null,
                "a 403 transition attempt must leave the persisted status untouched");
    }

    /**
     * Task 6 matrix — invoice writes and transitions. ADMIN and BILLING
     * register invoices and BILLING moves one to ISSUED; DOCTOR, NURSE,
     * and RECEPTIONIST are refused with 403, their refused creates persist
     * no invoice, and a refused transition leaves the persisted DRAFT
     * state untouched. The family stays a FINANCIAL SIMULATION.
     */
    @Test
    void invoiceWritesAdmitAdminAndBillingWhileClinicalRolesPersistNothing() {
        String adminToken = login(ADMIN);
        String patientId = createVerifiedPatientId(adminToken, "inv");

        String adminInvoiceId = requireBodyId(
                postJson("/api/invoices", login(ADMIN), invoiceCreatePayload("admin", patientId)),
                "the ADMIN invoice create");
        String billingInvoiceId = requireBodyId(
                postJson("/api/invoices", login(DENIED), invoiceCreatePayload("billing", patientId)),
                "the BILLING invoice create");

        ResponseEntity<Map<String, Object>> issued = putJson(
                "/api/invoices/" + billingInvoiceId + "/status", login(DENIED),
                Map.of("status", "ISSUED"));
        assertTrue(issued.getStatusCode().is2xxSuccessful(),
                "BILLING holds the invoice transition and must be admitted");
        assertEquals("ISSUED", issued.getBody() != null ? issued.getBody().get("status") : null,
                "the admitted transition must actually persist the server-owned status");

        long totalBefore = dashboardCount("invoices", adminToken);
        long draftBefore = dashboardCount("invoicesDraft", adminToken);
        for (String username : List.of(DOCTOR, NURSE, RECEPTIONIST)) {
            assertEquals(HttpStatus.FORBIDDEN,
                    postJson("/api/invoices", login(username), invoiceCreatePayload(username, patientId))
                            .getStatusCode(),
                    username + " must be refused the invoice create with 403");
        }
        assertEquals(totalBefore, dashboardCount("invoices", adminToken),
                "a 403 invoice-create attempt must not persist a record");
        assertEquals(draftBefore, dashboardCount("invoicesDraft", adminToken),
                "a 403 invoice-create attempt must not change the draft aggregate");

        assertEquals(HttpStatus.FORBIDDEN,
                putJson("/api/invoices/" + adminInvoiceId + "/status", login(RECEPTIONIST),
                        Map.of("status", "ISSUED")).getStatusCode(),
                "RECEPTIONIST must be refused the invoice transition with 403");
        ResponseEntity<Map<String, Object>> after = getJson("/api/invoices/" + adminInvoiceId, adminToken);
        assertEquals("DRAFT", after.getBody() != null ? after.getBody().get("status") : null,
                "a 403 transition attempt must leave the persisted DRAFT status untouched");
    }

    // ------------------------------------------------------------------
    // Plan 3 Task 3 (docs/plan3.md) — acting assignments and branch-bound
    // authentication context. These pins intentionally replace the Task 1
    // global-role baseline.
    // ------------------------------------------------------------------

    /** Full login-response body for a synthetic account, or null on refusal. */
    private Map<String, Object> loginBody(String username) {
        return loginBody(username, TEST_ACCOUNT_PASSWORD);
    }

    private Map<String, Object> loginBody(String username, String password) {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/api/auth/login", HttpMethod.POST, jsonLoginRequest(username, password), MAP);
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> exchangeLogin(String username, String password) {
        return rest.exchange("/api/auth/login", HttpMethod.POST, jsonLoginRequest(username, password), MAP);
    }

    private HttpEntity<Map<String, Object>> jsonLoginRequest(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("username", username, "password", password), headers);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> assignmentList(Map<String, Object> loginResponse) {
        return (List<Map<String, Object>>) loginResponse.get("assignments");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private HospitalOrganization testOrg() {
        return organizations.findByCode(TEST_ORG_CODE).orElseThrow();
    }

    private Branch testBranch(String code) {
        return branches.findByOrganizationIdAndCode(testOrg().getId(), code).orElseThrow();
    }

    private UUID assignmentIdFor(String username, Role role) {
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        return assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).stream()
                .filter(a -> a.getRole() == role)
                .findFirst().orElseThrow().getId();
    }

    private ResponseEntity<Map<String, Object>> switchContext(String token, UUID assignmentId, UUID branchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", assignmentId.toString());
        if (branchId != null) {
            payload.put("branchId", branchId.toString());
        }
        return rest.exchange("/api/auth/context", HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
    }

    private long contextSwitchEventCount() {
        return switchEventsFor(null);
    }

    /** Counts SWITCH events, optionally scoped to one assignment's resource id. */
    private long switchEventsFor(UUID assignmentId) {
        ResponseEntity<List<Map<String, Object>>> audit = getList("/api/audit", login(ADMIN));
        assertNotNull(audit.getBody(), "the audit list must carry a body");
        return audit.getBody().stream()
                .filter(e -> "ActingAssignment".equals(e.get("resourceType")) && "SWITCH".equals(e.get("action")))
                .filter(e -> assignmentId == null || assignmentId.toString().equals(e.get("resourceId")))
                .count();
    }

    /** Shared ApiError bodies carry a creation timestamp; equality compares the stable fields. */
    private Map<String, Object> withoutTimestamp(Map<String, Object> body) {
        Map<String, Object> stable = new LinkedHashMap<>(body);
        stable.remove("timestamp");
        return stable;
    }

    /**
     * Requirement 1: the login response is exactly the six-key allowlist,
     * roles carries only the selected assignment role, the assignment and
     * acting-context views are strict allowlists, and login deterministically
     * selects the first active branch in code order for the organization scope.
     */
    @Test
    void task3LoginCarriesExactlyTheAllowlistedShapeWithTheSelectedAssignmentRoleOnly() {
        Map<String, Object> body = loginBody(ADMIN);
        assertEquals(LOGIN_KEYS, body.keySet(), "the Task 3 login response is exactly the six-key allowlist");
        assertEquals(List.of("ADMIN"), body.get("roles"), "roles carries only the selected assignment role");
        assertEquals("Bearer", body.get("tokenType"));
        assertEquals(ADMIN, body.get("username"));
        assertFalse(body.containsKey("passwordHash"), "the hash never travels");

        List<Map<String, Object>> views = assignmentList(body);
        assertEquals(1, views.size(), "exactly one enabled assignment is listed");
        Map<String, Object> assignment = views.get(0);
        assertEquals(ASSIGNMENT_VIEW_KEYS, assignment.keySet(), "assignment views are the strict allowlist");
        assertEquals("ADMIN", assignment.get("role"));
        assertEquals("ORGANIZATION", assignment.get("scope"));
        assertNull(assignment.get("branchId"), "an organization-scope assignment has no fixed branch");
        assertEquals(Boolean.TRUE, assignment.get("enabled"));

        Map<String, Object> context = castMap(body.get("actingContext"));
        assertEquals(ACTING_CONTEXT_KEYS, context.keySet(), "the acting context is the strict allowlist");
        assertEquals(ADMIN, context.get("username"));
        assertEquals("ORGANIZATION", context.get("scope"));
        assertNull(context.get("departmentId"));
        assertEquals(testBranch(DEFAULT_BRANCH_CODE).getId().toString(), context.get("branchId"),
                "login deterministically selects the first active branch in code order");
        assertEquals(assignment.get("id"), context.get("assignmentId"));

        assertEquals(HttpStatus.OK, get("/api/dashboard", String.valueOf(body.get("accessToken"))).getStatusCode(),
                "the selected assignment authorizes the token immediately");
    }

    /**
     * Requirement 1: the legacy role union is never authority — an account
     * holding only legacy roles cannot log in at all, and an account with a
     * valid assignment keeps exactly the assignment role active.
     */
    @Test
    void legacyRoleUnionGrantsNothingWithoutAnAssignmentAndNeverAppearsAsAuthority() {
        String legacyOnly = "legacy-only-" + suffix;
        accounts.save(new UserAccount(legacyOnly, encoder.encode(TEST_ACCOUNT_PASSWORD),
                Set.of(Role.NURSE, Role.BILLING)));
        ResponseEntity<Map<String, Object>> refused = exchangeLogin(legacyOnly, TEST_ACCOUNT_PASSWORD);
        assertEquals(HttpStatus.UNAUTHORIZED, refused.getStatusCode(),
                "no legacy-role fallback: an account without an enabled assignment cannot log in");
        assertEquals(Map.of("error", INVALID_CREDENTIALS_BODY), refused.getBody(),
                "the refusal is the exact non-enumerating body");

        String legacyPlus = "legacy-plus-" + suffix;
        UserAccount account = accounts.save(new UserAccount(legacyPlus, encoder.encode(TEST_ACCOUNT_PASSWORD),
                Set.of(Role.NURSE, Role.BILLING)));
        assignments.save(ActingAssignment.branch(account, testOrg(), Role.NURSE,
                testBranch(DEFAULT_BRANCH_CODE)));

        Map<String, Object> body = loginBody(legacyPlus);
        assertEquals(List.of("NURSE"), body.get("roles"),
                "roles carries only the selected assignment role — never the legacy union");
        String token = String.valueOf(body.get("accessToken"));
        assertEquals(HttpStatus.OK, get("/api/patients", token).getStatusCode(),
                "the selected NURSE assignment grants the patients-family read");
        assertEquals(HttpStatus.FORBIDDEN, get("/api/invoices", token).getStatusCode(),
                "the legacy BILLING half must not become authority");
        assertEquals(HttpStatus.FORBIDDEN, get("/api/audit", token).getStatusCode(),
                "the legacy union must not reach the ADMIN-only audit surface");
    }

    /**
     * Requirement 2: a multi-assignment user logs in on the deterministic
     * first assignment, switches with a replacement token, and each token
     * carries exactly its own target role and fixed branch.
     */
    @Test
    void multiAssignmentUserSwitchesAssignmentsAndEachReplacementTokenCarriesExactlyTheTargetRole() {
        Branch defaultBranch = testBranch(DEFAULT_BRANCH_CODE);
        Branch otherBranch = testBranch(OTHER_BRANCH_CODE);
        UserAccount switcher = newDedicatedAccount("switcher", Role.NURSE, AssignmentScope.BRANCH,
                testOrg(), defaultBranch, null);
        assignments.save(ActingAssignment.branch(switcher, testOrg(), Role.RECEPTIONIST, otherBranch));

        Map<String, Object> login = loginBody(switcher.getUsername());
        assertEquals(List.of("NURSE"), login.get("roles"),
                "login deterministically selects the first assignment in role order");
        assertEquals(defaultBranch.getId().toString(), castMap(login.get("actingContext")).get("branchId"));
        assertEquals(2, assignmentList(login).size(), "both enabled assignments are listed");

        String nurseToken = String.valueOf(login.get("accessToken"));
        assertEquals(HttpStatus.FORBIDDEN,
                postJson("/api/patients", nurseToken, patientCreatePayload("switcher")).getStatusCode(),
                "the NURSE assignment cannot create patients");

        UUID receptionistAssignmentId = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(switcher.getId()).stream()
                .filter(a -> a.getRole() == Role.RECEPTIONIST).findFirst().orElseThrow().getId();
        ResponseEntity<Map<String, Object>> switched = switchContext(nurseToken, receptionistAssignmentId, null);
        assertEquals(HttpStatus.OK, switched.getStatusCode());
        Map<String, Object> switchedBody = switched.getBody();
        assertEquals(List.of("RECEPTIONIST"), switchedBody.get("roles"),
                "the replacement token carries exactly the target role");
        Map<String, Object> switchedContext = castMap(switchedBody.get("actingContext"));
        assertEquals(receptionistAssignmentId.toString(), switchedContext.get("assignmentId"));
        assertEquals(otherBranch.getId().toString(), switchedContext.get("branchId"),
                "a branch-scope assignment always acts on its own fixed branch");
        assertNotEquals(nurseToken, switchedBody.get("accessToken"), "the switch issues a replacement token");

        String receptionistToken = String.valueOf(switchedBody.get("accessToken"));
        assertTrue(postJson("/api/patients", receptionistToken, patientCreatePayload("switched")).getStatusCode().is2xxSuccessful(),
                "the RECEPTIONIST assignment holds the patient-create write");
        assertEquals(HttpStatus.OK, get("/api/dashboard", nurseToken).getStatusCode(),
                "the old token stays valid while its own assignment remains enabled");
    }

    /**
     * Recovery finding 2: the login and switch session views list only
     * enabled assignments that currently pass the same invariants used for
     * authorization — an enabled but inconsistent row is omitted, never
     * rendered — while the valid selected assignment stays present.
     */
    @Test
    void loginAndSwitchListOnlyCurrentlyValidAssignments() {
        HospitalOrganization foreignOrganization = organizations.save(new HospitalOrganization(
                "AUTHZ-ORG-VIEW-" + suffix, "View Foreign Hospital"));
        Branch foreignBranch = branches.save(
                new Branch(foreignOrganization, "AUTHZ-BR-VIEW-" + suffix, "Foreign View Branch", "Elsewhere"));
        Department foreignDepartment = departments.save(new Department(
                foreignBranch, "AUTHZ-DEP-VIEW-" + suffix, "Foreign View Department", "general", "9 Foreign Way"));

        UserAccount account = newDedicatedAccount("mixed-validity", Role.NURSE, AssignmentScope.BRANCH,
                testOrg(), testBranch(DEFAULT_BRANCH_CODE), null);
        ActingAssignment valid = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).get(0);
        ActingAssignment inconsistent = assignments.save(
                ActingAssignment.department(account, testOrg(), Role.RECEPTIONIST, foreignDepartment));
        assertTrue(inconsistent.isEnabled(), "the second assignment is enabled — it is omitted for inconsistency only");

        Map<String, Object> login = loginBody(account.getUsername());
        assertEquals(List.of("NURSE"), login.get("roles"), "login selects the deterministic valid assignment");
        List<String> loginIds = assignmentList(login).stream().map(v -> String.valueOf(v.get("id"))).toList();
        assertEquals(List.of(valid.getId().toString()), loginIds,
                "the login assignments list contains exactly the valid row and omits the enabled inconsistent one");

        ResponseEntity<Map<String, Object>> switched = switchContext(
                String.valueOf(login.get("accessToken")), valid.getId(), null);
        assertEquals(HttpStatus.OK, switched.getStatusCode());
        List<String> switchIds = assignmentList(switched.getBody()).stream()
                .map(v -> String.valueOf(v.get("id"))).toList();
        assertEquals(List.of(valid.getId().toString()), switchIds,
                "the switch response obeys the same filtered-list contract");
    }

    /**
     * Requirement 3: an organization-scoped assignment selects active
     * branches only inside its own organization; inactive and unknown
     * branches are refused.
     */
    @Test
    void organizationScopeSwitchesOnlyBetweenActiveBranchesInsideItsOrganization() {
        UUID adminAssignment = assignmentIdFor(ADMIN, Role.ADMIN);
        String token = login(ADMIN);

        ResponseEntity<Map<String, Object>> switched =
                switchContext(token, adminAssignment, testBranch(OTHER_BRANCH_CODE).getId());
        assertEquals(HttpStatus.OK, switched.getStatusCode(),
                "an organization-scope assignment may select another active branch inside its organization");
        assertEquals(testBranch(OTHER_BRANCH_CODE).getId().toString(),
                castMap(switched.getBody().get("actingContext")).get("branchId"));

        HospitalOrganization foreignOrg = organizations.save(
                new HospitalOrganization("AUTHZ-ORG-FOREIGN-" + suffix, "Foreign Hospital"));
        Branch foreignBranch = branches.save(
                new Branch(foreignOrg, "AUTHZ-BR-FOREIGN-" + suffix, "Foreign Branch", "Elsewhere"));
        assertEquals(HttpStatus.FORBIDDEN, switchContext(token, adminAssignment, foreignBranch.getId()).getStatusCode(),
                "a branch of another organization is refused");

        Branch dormant = branches.save(new Branch(testOrg(), "AUTHZ-BR-DORMANT-" + suffix, "Dormant Branch", "Closed"));
        jdbc.update("update branches set active = false where id = ?", dormant.getId());
        assertEquals(HttpStatus.FORBIDDEN, switchContext(token, adminAssignment, dormant.getId()).getStatusCode(),
                "an inactive branch of the same organization is refused");
        assertEquals(HttpStatus.FORBIDDEN, switchContext(token, adminAssignment, UUID.randomUUID()).getStatusCode(),
                "an unknown branch is refused");
    }

    /**
     * Requirement 4 (part 1): foreign and unknown assignment switches are
     * 403, indistinguishable, and record no audit event.
     */
    @Test
    void foreignAndUnknownAssignmentSwitchesAreForbiddenWithoutAuditAndWithoutEnumeration() {
        long eventsBefore = contextSwitchEventCount();
        String nurseToken = login(NURSE);
        ResponseEntity<Map<String, Object>> foreign =
                switchContext(nurseToken, assignmentIdFor(ADMIN, Role.ADMIN), null);
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatusCode(), "another user's assignment is refused");
        ResponseEntity<Map<String, Object>> unknown = switchContext(nurseToken, UUID.randomUUID(), null);
        assertEquals(HttpStatus.FORBIDDEN, unknown.getStatusCode(), "an unknown assignment is refused");
        assertEquals(withoutTimestamp(foreign.getBody()), withoutTimestamp(unknown.getBody()),
                "foreign and unknown refusals are indistinguishable — no existence leak");
        assertEquals(eventsBefore, contextSwitchEventCount(), "failed switches record no audit event");
    }

    /**
     * Requirement 4 (part 2): a disabled assignment invalidates its bound
     * token immediately (401 on requests and on switching) and refuses any
     * further login for the account.
     */
    @Test
    void disabledAssignmentInvalidatesItsTokenAndRefusesSwitching() {
        UserAccount account = newDedicatedAccount("disposable-disabled", Role.NURSE, AssignmentScope.BRANCH,
                testOrg(), testBranch(DEFAULT_BRANCH_CODE), null);
        ActingAssignment assignment = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).get(0);
        String token = login(account.getUsername());
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode());

        assignment.disable();
        assignments.save(assignment);

        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", token).getStatusCode(),
                "a token bound to a disabled assignment is unauthenticated immediately");
        assertEquals(HttpStatus.UNAUTHORIZED, switchContext(token, assignment.getId(), null).getStatusCode(),
                "switching from a dead token stays on the 401 boundary");
        ResponseEntity<Map<String, Object>> relogin = exchangeLogin(account.getUsername(), TEST_ACCOUNT_PASSWORD);
        assertEquals(HttpStatus.UNAUTHORIZED, relogin.getStatusCode(),
                "a disabled assignment removes the only login path");
        assertEquals(Map.of("error", INVALID_CREDENTIALS_BODY), relogin.getBody());
    }

    /**
     * Requirement 4 (part 3): a deleted assignment invalidates its bound
     * token immediately.
     */
    @Test
    void deletedAssignmentInvalidatesItsTokenImmediately() {
        UserAccount account = newDedicatedAccount("disposable-deleted", Role.NURSE, AssignmentScope.BRANCH,
                testOrg(), testBranch(DEFAULT_BRANCH_CODE), null);
        ActingAssignment assignment = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).get(0);
        String token = login(account.getUsername());
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode());

        assignments.delete(assignment);

        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", token).getStatusCode(),
                "a token bound to a deleted assignment is unauthenticated immediately");
        assertEquals(HttpStatus.UNAUTHORIZED, switchContext(token, assignment.getId(), null).getStatusCode(),
                "switching from the invalidated token stays unauthenticated");
    }

    /**
     * Requirement 4 (part 4): a disabled account fails closed on the
     * request path and on re-login with the non-enumerating body.
     */
    @Test
    void disabledAccountFailsClosedOnEveryPath() {
        UserAccount account = newDedicatedAccount("disposable-account", Role.NURSE, AssignmentScope.BRANCH,
                testOrg(), testBranch(DEFAULT_BRANCH_CODE), null);
        String token = login(account.getUsername());
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode());

        account.deactivate();
        accounts.save(account);

        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", token).getStatusCode(),
                "a disabled account invalidates its token immediately");
        ResponseEntity<Map<String, Object>> relogin = exchangeLogin(account.getUsername(), TEST_ACCOUNT_PASSWORD);
        assertEquals(HttpStatus.UNAUTHORIZED, relogin.getStatusCode());
        assertEquals(Map.of("error", INVALID_CREDENTIALS_BODY), relogin.getBody(),
                "the disabled-account refusal is indistinguishable from bad credentials");
    }

    /**
     * Requirement 4 (part 5): the selected branch of an organization-scope
     * assignment must stay active and exist — deactivating or deleting it
     * invalidates the token, and login fails closed with no eligible branch.
     */
    @Test
    void inactiveOrDeletedSelectedBranchFailsClosed() {
        HospitalOrganization org = organizations.save(new HospitalOrganization(
                "AUTHZ-ORG-BRANCH-STATE-" + suffix, "Branch State Hospital"));
        Branch only = branches.save(new Branch(org, "AUTHZ-BR-ONLY-" + suffix, "Only Branch", "Nowhere"));
        UserAccount account = newDedicatedAccount("branch-state", Role.STAFF, AssignmentScope.ORGANIZATION, org, null, null);
        String token = login(account.getUsername());
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode());

        jdbc.update("update branches set active = false where id = ?", only.getId());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", token).getStatusCode(),
                "an inactive selected branch invalidates the acting context immediately");
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeLogin(account.getUsername(), TEST_ACCOUNT_PASSWORD).getStatusCode(),
                "no eligible active branch — login fails closed");

        branches.deleteById(only.getId());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", token).getStatusCode(),
                "a deleted selected branch invalidates the acting context");
    }

    /**
     * Requirement 4 (part 6): department relationships that contradict the
     * assignment fail closed — login refuses the account when the broken
     * assignment is the only one, and switching to it is 403 when a valid
     * assignment exists alongside.
     */
    @Test
    void inconsistentDepartmentAssignmentsFailClosed() {
        HospitalOrganization foreignOrg = organizations.save(new HospitalOrganization(
                "AUTHZ-ORG-DEPT-" + suffix, "Department Foreign Hospital"));
        Branch foreignBranch = branches.save(
                new Branch(foreignOrg, "AUTHZ-BR-DEPT-" + suffix, "Foreign Department Branch", "Elsewhere"));
        Department foreignDepartment = departments.save(new Department(
                foreignBranch, "AUTHZ-DEP-" + suffix, "Foreign Department", "general", "1 Foreign Way"));

        UserAccount crossOrg = newDedicatedAccount("dept-cross", Role.NURSE, AssignmentScope.DEPARTMENT,
                testOrg(), null, foreignDepartment);
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeLogin(crossOrg.getUsername(), TEST_ACCOUNT_PASSWORD).getStatusCode(),
                "a department inconsistent with the assignment organization fails login closed");

        Department nulledDepartment = departments.save(new Department(
                testBranch(DEFAULT_BRANCH_CODE), "AUTHZ-DEP-NULL-" + suffix, "Nulled Department", "general",
                "3 Null Way"));
        UserAccount departmentless = newDedicatedAccount("dept-null", Role.NURSE, AssignmentScope.DEPARTMENT,
                testOrg(), null, nulledDepartment);
        // Destructive persisted-row corruption (no API exists): a DEPARTMENT row whose department was nulled.
        jdbc.update("update acting_assignments set department_id = null where id = ?",
                assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(departmentless.getId())
                        .get(0).getId());
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeLogin(departmentless.getUsername(), TEST_ACCOUNT_PASSWORD).getStatusCode(),
                "a department-scope assignment without a department fails login closed");

        UserAccount mixed = newDedicatedAccount("dept-mixed", Role.NURSE, AssignmentScope.BRANCH,
                testOrg(), testBranch(DEFAULT_BRANCH_CODE), null);
        ActingAssignment broken = assignments.save(
                ActingAssignment.department(mixed, testOrg(), Role.DOCTOR, foreignDepartment));
        String token = login(mixed.getUsername());
        assertEquals(HttpStatus.FORBIDDEN, switchContext(token, broken.getId(), null).getStatusCode(),
                "an inconsistent department assignment is refused at switch");
    }

    /**
     * Requirement 5: exactly one safe audit event per successful switch,
     * none per failure, and no token or credential material in the event.
     */
    @Test
    void contextSwitchEmitsExactlyOneSafeAuditEventAndFailuresEmitNone() {
        String token = login(ADMIN);
        UUID adminAssignment = assignmentIdFor(ADMIN, Role.ADMIN);
        long before = contextSwitchEventCount();
        long beforeForAssignment = switchEventsFor(adminAssignment);

        ResponseEntity<Map<String, Object>> switched =
                switchContext(token, adminAssignment, testBranch(OTHER_BRANCH_CODE).getId());
        assertEquals(HttpStatus.OK, switched.getStatusCode());
        assertEquals(before + 1, contextSwitchEventCount(), "exactly one event per successful switch");
        assertEquals(beforeForAssignment + 1, switchEventsFor(adminAssignment),
                "exactly one new switch event exists for the assignment");

        ResponseEntity<List<Map<String, Object>>> audit = getList("/api/audit", token);
        List<Map<String, Object>> events = audit.getBody().stream()
                .filter(e -> "ActingAssignment".equals(e.get("resourceType"))
                        && adminAssignment.toString().equals(e.get("resourceId"))
                        && "SWITCH".equals(e.get("action")))
                .toList();
        assertEquals(beforeForAssignment + 1, events.size(),
                "the switch added exactly one event and nothing else did");
        Map<String, Object> event = events.get(events.size() - 1);
        assertEquals(ADMIN, event.get("actor"));
        assertNotNull(event.get("occurredAt"));
        String details = String.valueOf(event.get("details"));
        assertFalse(details.contains("eyJ"), "no token material is ever recorded");
        assertFalse(details.toLowerCase().contains("password"), "no password material is recorded");
        assertFalse(details.contains("BILLING"), "no role union is recorded");

        assertEquals(HttpStatus.FORBIDDEN, switchContext(token, UUID.randomUUID(), null).getStatusCode(),
                "an unknown target is refused");
        assertEquals(before + 1, contextSwitchEventCount(), "failed switches record no audit event");
    }

    /**
     * Requirement 6: a properly signed token with an altered role claim
     * derives exactly one authority from server assignment state — the
     * claim can neither widen nor narrow it.
     */
    @Test
    void alteredRoleClaimsCannotWidenOrNarrowServerDerivedAuthority() {
        HospitalOrganization org = testOrg();
        Branch defaultBranch = testBranch(DEFAULT_BRANCH_CODE);

        String widened = forgedToken(NURSE, assignmentIdFor(NURSE, Role.NURSE), "ADMIN", "BRANCH",
                org.getId(), defaultBranch.getId());
        assertEquals(HttpStatus.FORBIDDEN, get("/api/audit", widened).getStatusCode(),
                "a forged elevated role claim cannot widen authority beyond the server assignment");
        assertEquals(HttpStatus.OK, get("/api/nursing-observations", widened).getStatusCode(),
                "the server-derived NURSE authority still authorizes the nurse family");

        String narrowed = forgedToken(ADMIN, assignmentIdFor(ADMIN, Role.ADMIN), "NURSE", "ORGANIZATION",
                org.getId(), defaultBranch.getId());
        assertEquals(HttpStatus.OK, get("/api/audit", narrowed).getStatusCode(),
                "a forged downgraded role claim cannot narrow the server-derived ADMIN authority");
    }

    /**
     * Recovery finding 1 (scope): a correctly signed token whose scope claim
     * was altered cannot authenticate — every structural claim must match
     * the rebuilt server assignment, while the unaltered control token
     * proves the forging mechanism itself authenticates.
     */
    @Test
    void tamperedScopeClaimLeavesRequestsUnauthenticated() {
        UUID nurseAssignment = assignmentIdFor(NURSE, Role.NURSE);
        UUID organizationId = testOrg().getId();
        UUID defaultBranchId = testBranch(DEFAULT_BRANCH_CODE).getId();

        String control = forgedToken(NURSE, nurseAssignment, "NURSE", "BRANCH", organizationId, defaultBranchId);
        assertEquals(HttpStatus.OK, get("/api/dashboard", control).getStatusCode(),
                "the correctly signed structural token authenticates (tampering-mechanism control)");
        String tampered = forgedToken(NURSE, nurseAssignment, "NURSE", "ORGANIZATION",
                organizationId, defaultBranchId);
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", tampered).getStatusCode(),
                "an altered scope claim cannot authenticate — it must match the server assignment");
    }

    /**
     * Recovery finding 1 (organization): a correctly signed token claiming
     * another organization is unauthenticated even though subject,
     * assignment, and branch remain valid — the rebuilt server organization
     * must equal the claim.
     */
    @Test
    void tamperedOrganizationClaimLeavesRequestsUnauthenticated() {
        HospitalOrganization foreignOrganization = organizations.save(new HospitalOrganization(
                "AUTHZ-ORG-STRUCT-" + suffix, "Structural Tamper Hospital"));
        UUID nurseAssignment = assignmentIdFor(NURSE, Role.NURSE);
        UUID organizationId = testOrg().getId();
        UUID defaultBranchId = testBranch(DEFAULT_BRANCH_CODE).getId();

        String control = forgedToken(NURSE, nurseAssignment, "NURSE", "BRANCH", organizationId, defaultBranchId);
        assertEquals(HttpStatus.OK, get("/api/dashboard", control).getStatusCode(),
                "the correctly signed structural token authenticates (tampering-mechanism control)");
        String tampered = forgedToken(NURSE, nurseAssignment, "NURSE", "BRANCH",
                foreignOrganization.getId(), defaultBranchId);
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", tampered).getStatusCode(),
                "an altered organizationId claim cannot authenticate — it must match the server assignment");
    }

    /**
     * Recovery finding 1 (department): a department-scope token with exactly
     * matching structural claims authenticates, but an altered or missing
     * departmentId claim is unauthenticated — including the null semantics
     * of the optional claim.
     */
    @Test
    void tamperedOrMissingDepartmentClaimLeavesDepartmentScopeUnauthenticated() {
        Branch defaultBranch = testBranch(DEFAULT_BRANCH_CODE);
        Department realDepartment = departments.save(new Department(
                defaultBranch, "AUTHZ-DEP-REAL-" + suffix, "Real Department", "general", "5 Real Way"));
        UserAccount account = newDedicatedAccount("dept-claim", Role.NURSE, AssignmentScope.DEPARTMENT,
                testOrg(), null, realDepartment);
        UUID assignmentId = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).get(0).getId();
        UUID organizationId = testOrg().getId();

        String control = forgedToken(account.getUsername(), assignmentId, "NURSE", "DEPARTMENT",
                organizationId, defaultBranch.getId(), realDepartment.getId());
        assertEquals(HttpStatus.OK, get("/api/dashboard", control).getStatusCode(),
                "a department-scope token with exactly matching structural claims authenticates");

        HospitalOrganization foreignOrganization = organizations.save(new HospitalOrganization(
                "AUTHZ-ORG-DEPT-CLAIM-" + suffix, "Department Claim Hospital"));
        Branch foreignBranch = branches.save(
                new Branch(foreignOrganization, "AUTHZ-BR-DEPT-CLAIM-" + suffix, "Foreign Claim Branch", "Elsewhere"));
        Department foreignDepartment = departments.save(new Department(
                foreignBranch, "AUTHZ-DEP-CLAIM-" + suffix, "Foreign Claim Department", "general", "7 Foreign Way"));
        String tampered = forgedToken(account.getUsername(), assignmentId, "NURSE", "DEPARTMENT",
                organizationId, defaultBranch.getId(), foreignDepartment.getId());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", tampered).getStatusCode(),
                "an altered departmentId claim cannot authenticate a department-scope token");

        String missing = forgedToken(account.getUsername(), assignmentId, "NURSE", "DEPARTMENT",
                organizationId, defaultBranch.getId(), null);
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", missing).getStatusCode(),
                "a missing departmentId claim fails the structural equality — department null semantics hold");
    }

    /** Builds a correctly signed token with arbitrary (possibly tampered) claims. */
    private String forgedToken(String username, UUID assignmentId, String claimedRole, String claimedScope,
                               UUID organizationId, UUID branchId) {
        return forgedToken(username, assignmentId, claimedRole, claimedScope, organizationId, branchId, null);
    }

    /** Builds a correctly signed token with arbitrary claims; a null departmentId omits the optional claim. */
    private String forgedToken(String username, UUID assignmentId, String claimedRole, String claimedScope,
                               UUID organizationId, UUID branchId, UUID departmentId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(username)
                .claim("assignmentId", assignmentId.toString())
                .claim("role", claimedRole)
                .claim("scope", claimedScope)
                .claim("organizationId", organizationId.toString())
                .claim("branchId", branchId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(30))));
        if (departmentId != null) {
            builder.claim("departmentId", departmentId.toString());
        }
        return builder.signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /**
     * Requirement 7: context headers and switch-body tampering cannot widen
     * branch or role scope — authority and switching follow only the token
     * and the server-verified assignment.
     */
    @Test
    void contextHeadersAndBodyTamperingCarryNoAuthority() {
        String nurseToken = login(NURSE);
        HttpHeaders decorated = new HttpHeaders();
        decorated.setBearerAuth(nurseToken);
        decorated.set("X-Acting-Role", "ADMIN");
        decorated.set("X-Acting-Assignment-Id", assignmentIdFor(ADMIN, Role.ADMIN).toString());
        decorated.set("X-Acting-Branch-Id", UUID.randomUUID().toString());
        assertEquals(HttpStatus.FORBIDDEN,
                rest.exchange("/api/audit", HttpMethod.GET, new HttpEntity<>(decorated), String.class).getStatusCode(),
                "context headers cannot elevate authority");
        assertEquals(HttpStatus.OK,
                rest.exchange("/api/nursing-observations", HttpMethod.GET, new HttpEntity<>(decorated), String.class)
                        .getStatusCode(),
                "the token's own server-derived authority is unaffected by context headers");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(nurseToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> tampered = Map.of(
                "assignmentId", assignmentIdFor(ADMIN, Role.ADMIN).toString(),
                "branchId", testBranch(OTHER_BRANCH_CODE).getId().toString());
        assertEquals(HttpStatus.FORBIDDEN,
                rest.exchange("/api/auth/context", HttpMethod.POST, new HttpEntity<>(tampered, headers), MAP)
                        .getStatusCode(),
                "a tampered switch body cannot select another user's assignment");
    }

    /**
     * Requirement 8: bad username, wrong password, and (from the disabled
     * account test) disabled users all answer the same non-enumerating 401.
     */
    @Test
    void credentialFailuresStayNonEnumerating401() {
        for (HttpEntity<Map<String, Object>> attempt : List.of(
                jsonLoginRequest(ADMIN, "definitely-wrong-" + suffix),
                jsonLoginRequest("no-such-user-" + suffix, TEST_ACCOUNT_PASSWORD))) {
            ResponseEntity<Map<String, Object>> response =
                    rest.exchange("/api/auth/login", HttpMethod.POST, attempt, MAP);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertEquals(Map.of("error", INVALID_CREDENTIALS_BODY), response.getBody(),
                    "bad username and wrong password stay one indistinguishable 401");
        }
    }
}

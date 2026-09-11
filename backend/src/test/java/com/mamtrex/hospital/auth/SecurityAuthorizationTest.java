package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
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
 * Plan 3 Task 1 (docs/plan3.md) adds a narrow baseline pin for the global
 * role model that Plan 3 Task 3 will intentionally change: a successful
 * login carries no assignments and no acting context, no assignment header
 * is required for an authorized call, and an account holding several roles
 * exercises the union of those roles globally. The existing care-operations
 * matrix above is neither weakened nor duplicated by these pins.
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

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void seedTestAccounts() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        if (accounts.findByUsername(NURSE).isEmpty()) {
            accounts.save(new UserAccount(NURSE, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        }
        if (accounts.findByUsername(DOCTOR).isEmpty()) {
            accounts.save(new UserAccount(DOCTOR, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.DOCTOR)));
        }
        if (accounts.findByUsername(RECEPTIONIST).isEmpty()) {
            accounts.save(new UserAccount(RECEPTIONIST, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.RECEPTIONIST)));
        }
        if (accounts.findByUsername(DENIED).isEmpty()) {
            accounts.save(new UserAccount(DENIED, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.BILLING)));
        }
        if (accounts.findByUsername(LAB).isEmpty()) {
            accounts.save(new UserAccount(LAB, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.LAB_TECH)));
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
    // Plan 3 Task 1 baseline (docs/plan3.md Task 1) — the global role
    // model that Plan 3 Task 3 will deliberately replace with acting
    // assignments and a branch-bound context. Characterization only.
    // ------------------------------------------------------------------

    /**
     * PLAN 3 BASELINE (Task 3 will intentionally change it): a successful
     * login answers with exactly the global token/username/roles shape — no
     * {@code assignments}, no {@code actingContext} — and the resulting
     * token authorizes an allowed endpoint with no assignment or branch
     * header of any kind.
     */
    @Test
    void plan3BaselineLoginCarriesNoAssignmentOrActingContextAndNoneIsRequired() {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", ADMIN, "password", TEST_ACCOUNT_PASSWORD), loginHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(Set.of("accessToken", "tokenType", "username", "roles"), body.keySet(),
                "today's login response is the global-role shape only");
        assertFalse(body.containsKey("assignments"), "no acting assignments exist today");
        assertFalse(body.containsKey("actingContext"), "no acting context exists today");
        String token = String.valueOf(body.get("accessToken"));
        assertEquals(HttpStatus.OK, get("/api/dashboard", token).getStatusCode(),
                "an authorized call must succeed with only the bearer token and no assignment header");
    }

    /**
     * PLAN 3 BASELINE (Task 3 will intentionally change it): an account
     * holding several global roles exercises the union of those roles on
     * every matching endpoint family with no acting assignment — the NURSE
     * half grants the patients-family read and the BILLING half grants the
     * invoice family, both from one plain bearer token. A disposable
     * multi-role account with a unique name keeps the single-role matrix
     * above untouched.
     */
    @Test
    void plan3BaselineGlobalRoleUnionGrantsEveryHeldRoleWithoutAnyAssignment() {
        String union = "plan3-union-" + suffix;
        if (accounts.findByUsername(union).isEmpty()) {
            accounts.save(new UserAccount(union, encoder.encode(TEST_ACCOUNT_PASSWORD),
                    Set.of(Role.NURSE, Role.BILLING)));
        }
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", union, "password", TEST_ACCOUNT_PASSWORD), loginHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        List<?> roles = assertInstanceOf(List.class, body.get("roles"),
                "login must list the account's global role names");
        assertTrue(roles.contains("NURSE") && roles.contains("BILLING"),
                "the login roles list must carry the full global union of held roles");
        String token = String.valueOf(body.get("accessToken"));
        assertEquals(HttpStatus.OK, get("/api/patients", token).getStatusCode(),
                "the NURSE half of the global union must grant the patients-family read");
        assertEquals(HttpStatus.OK, get("/api/invoices", token).getStatusCode(),
                "the BILLING half of the global union must grant the invoice-family read");
    }
}

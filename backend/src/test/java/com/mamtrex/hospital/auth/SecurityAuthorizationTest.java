package com.mamtrex.hospital.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
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
    /** BILLING is deliberately outside every patients/appointments path rule. */
    private static final String DENIED = "billing1";

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
}

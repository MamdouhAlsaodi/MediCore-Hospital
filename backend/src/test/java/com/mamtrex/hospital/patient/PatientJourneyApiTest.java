package com.mamtrex.hospital.patient;

import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization test (docs/plan1.md Task 1): freezes the CURRENT patient,
 * appointment, authorization, and audit contracts without improving them.
 * Runs against an isolated in-memory H2 database (never the production file
 * store) with disposable synthetic test-only secrets and fabricated record
 * values; no real personal or clinical data is ever used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:patient-journey-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + PatientJourneyApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + PatientJourneyApiTest.TEST_ACCOUNT_PASSWORD
})
class PatientJourneyApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-journey-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-journey-01";

    private static final String RECEPTIONIST = "journey-receptionist";
    private static final String ADMIN_USER = "journey-admin";
    /** STAFF is deliberately NOT in the /api/patients/** allowed role set. */
    private static final String DENIED = "journey-denied-staff";

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
        if (accounts.findByUsername(RECEPTIONIST).isEmpty()) {
            accounts.save(new UserAccount(RECEPTIONIST, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.RECEPTIONIST)));
        }
        if (accounts.findByUsername(ADMIN_USER).isEmpty()) {
            accounts.save(new UserAccount(ADMIN_USER, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        if (accounts.findByUsername(DENIED).isEmpty()) {
            accounts.save(new UserAccount(DENIED, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.STAFF)));
        }
    }

    @Test
    void anonymousPatientListIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, getStatus("/api/patients", null).getStatusCode());
    }

    @Test
    void receptionistCreatesThenSearchesPatientByFullName() {
        String token = login(RECEPTIONIST);
        String mrn = "MRN-" + suffix + "-A";
        String fullName = "Test Patient Alpha " + suffix;
        ResponseEntity<Map<String, Object>> created = post("/api/patients", token, Map.ofEntries(
                Map.entry("medicalRecordNumber", mrn),
                Map.entry("fullName", fullName),
                Map.entry("dateOfBirth", "2011-02-03"),
                Map.entry("sex", "unspecified"),
                Map.entry("phone", "+10000000001"),
                Map.entry("email", "alpha-" + suffix + "@synthetic.test"),
                Map.entry("nationalId", "NID-" + suffix + "-A"),
                Map.entry("address", "1 Synthetic Street")));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertNotNull(body.get("id"), "created patient must carry its UUID identity");
        assertEquals(mrn, body.get("medicalRecordNumber"));
        assertEquals(fullName, body.get("fullName"));
        assertEquals("2011-02-03", body.get("dateOfBirth"));
        assertEquals("unspecified", body.get("sex"));
        assertEquals(Boolean.TRUE, body.get("active"));

        post("/api/patients", token, Map.of(
                "medicalRecordNumber", "MRN-" + suffix + "-B",
                "fullName", "Test Patient Beta " + suffix));

        ResponseEntity<List<Map<String, Object>>> search = getSearch("/api/patients?q={q}", token, "alpha " + suffix);
        assertEquals(HttpStatus.OK, search.getStatusCode());
        List<Map<String, Object>> matches = search.getBody();
        assertEquals(1, matches == null ? -1 : matches.size(), "search must match only the Alpha patient");
        assertEquals(mrn, matches.get(0).get("medicalRecordNumber"));
        assertEquals(fullName, matches.get(0).get("fullName"));
    }

    @Test
    void staffRoleIsForbiddenFromPatientEndpoints() {
        String token = login(DENIED);
        assertEquals(HttpStatus.FORBIDDEN, getStatus("/api/patients", token).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("/api/patients", token, Map.of(
                "medicalRecordNumber", "MRN-" + suffix + "-DENIED",
                "fullName", "Denied Synthetic " + suffix)).getStatusCode());
    }

    /**
     * Why: this pins a KNOWN WEAK contract. Today POST /api/appointments
     * persists any non-blank strings as patientId/professionalId without
     * proving the referenced records exist. docs/plan1.md Task 4 intentionally
     * changes this behavior; this test is characterization only and must be
     * updated together with that task, never treated as an endorsement.
     */
    @Test
    void appointmentCreationCurrentlyAcceptsArbitraryNonblankStringReferences() {
        String token = login(RECEPTIONIST);
        String fakePatientId = "not-a-real-patient-" + suffix;
        String fakeProfessionalId = "not-a-real-professional-" + suffix;
        ResponseEntity<Map<String, Object>> res = post("/api/appointments", token, Map.of(
                "patientId", fakePatientId,
                "professionalId", fakeProfessionalId,
                "scheduledAt", "2031-01-01T09:00:00",
                "type", "consultation",
                "status", "scheduled"));
        assertEquals(HttpStatus.OK, res.getStatusCode(), "current contract accepts unresolved string references");
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertNotNull(body.get("id"));
        assertEquals(fakePatientId, body.get("patientId"));
        assertEquals(fakeProfessionalId, body.get("professionalId"));
        assertEquals("consultation", body.get("type"));
        assertEquals("scheduled", body.get("status"));
    }

    @Test
    void adminObservesCreateAuditEventTiedToCreatedPatient() {
        String receptionistToken = login(RECEPTIONIST);
        String mrn = "MRN-" + suffix + "-AUD";
        ResponseEntity<Map<String, Object>> created = post("/api/patients", receptionistToken, Map.of(
                "medicalRecordNumber", mrn,
                "fullName", "Test Patient Audit " + suffix));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> createdBody = created.getBody();
        assertNotNull(createdBody);
        String patientId = String.valueOf(createdBody.get("id"));

        ResponseEntity<List<Map<String, Object>>> audit = getList("/api/audit", login(ADMIN_USER));
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        List<Map<String, Object>> events = audit.getBody().stream()
                .filter(e -> "Patient".equals(e.get("resourceType")) && patientId.equals(e.get("resourceId")))
                .collect(Collectors.toList());
        assertEquals(1, events.size(), "exactly one audit event must exist for the created resource");
        Map<String, Object> event = events.get(0);
        assertEquals("CREATE", event.get("action"));
        assertEquals(RECEPTIONIST, event.get("actor"));
        assertEquals(mrn, event.get("details"));
        assertNotNull(event.get("occurredAt"));
    }

    private String login(String username) {
        ResponseEntity<Map<String, Object>> res = post("/api/auth/login", null, Map.of(
                "username", username, "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, res.getStatusCode(), "login should succeed for " + username);
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        return String.valueOf(body.get("accessToken"));
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token, Map<String, Object> payload) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** Error responses carry a JSON error object, so status checks read the raw body. */
    private ResponseEntity<String> getStatus(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getSearch(String path, String token, String query) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}, query);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}

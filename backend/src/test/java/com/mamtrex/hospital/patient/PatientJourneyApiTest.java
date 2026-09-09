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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Patient Journey contract tests.
 *
 * Task 1 established the original characterization baseline. Task 3
 * (docs/plan1.md) now pins the normalized DTO contracts: Patient Journey
 * routes return immutable response DTOs without persistence internals
 * (version/createdAt/updatedAt), malformed UUID paths are client errors,
 * blank/invalid request values return 400, appointment scheduledAt is a
 * typed ISO LocalDateTime, and appointment status is bound to the explicit
 * lowercase contract scheduled|confirmed|completed|cancelled. Runs against
 * an isolated in-memory H2 database (never the production file store) with
 * disposable synthetic test-only secrets and fabricated record values; no
 * real personal or clinical data is ever used.
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

    /** Stable public Patient DTO contract; persistence internals stay out. */
    private static final Set<String> PATIENT_CONTRACT_FIELDS = Set.of(
            "id", "medicalRecordNumber", "fullName", "dateOfBirth", "sex",
            "phone", "email", "nationalId", "address", "active");

    /** Stable public Appointment DTO contract; raw references survive until Task 4. */
    private static final Set<String> APPOINTMENT_CONTRACT_FIELDS = Set.of(
            "id", "patientId", "professionalId", "scheduledAt", "type", "status");

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
        assertNotNull(matches, "search response must carry a body");
        assertEquals(1, matches.size(), "search must match only the Alpha patient");
        assertEquals(mrn, matches.get(0).get("medicalRecordNumber"));
        assertEquals(fullName, matches.get(0).get("fullName"));
    }

    @Test
    void patientResponsesExposeStableDtoFieldsWithoutPersistenceInternals() {
        String token = login(RECEPTIONIST);
        ResponseEntity<Map<String, Object>> created = post("/api/patients", token, fullPatientPayload("-DTO"));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(PATIENT_CONTRACT_FIELDS, body.keySet(), "create response must match the stable DTO contract exactly");

        String patientId = String.valueOf(body.get("id"));
        ResponseEntity<Map<String, Object>> detail = getMap("/api/patients/" + patientId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        Map<String, Object> detailBody = detail.getBody();
        assertNotNull(detailBody);
        assertEquals(PATIENT_CONTRACT_FIELDS, detailBody.keySet(), "detail response must match the stable DTO contract exactly");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/patients", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "patient list must carry a body");
        assertFalse(listBody.isEmpty(), "patient list must be non-empty");
        for (Map<String, Object> item : listBody) {
            assertEquals(PATIENT_CONTRACT_FIELDS, item.keySet(), "every list item must match the stable DTO contract exactly");
        }
    }

    @Test
    void malformedUuidPathReturnsClientErrorNotServerError() {
        String token = login(RECEPTIONIST);
        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/patients/not-a-uuid", token).getStatusCode(),
                "malformed patient UUID must be a client error, never a 500");
        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/appointments/not-a-uuid", token).getStatusCode(),
                "malformed appointment UUID must be a client error, never a 500");
        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/staff/not-a-uuid", login(ADMIN_USER)).getStatusCode(),
                "malformed staff UUID must be a client error, never a 500");
    }

    @Test
    void blankRequiredPatientValuesReturn400() {
        String token = login(RECEPTIONIST);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/patients", token, Map.of(
                "medicalRecordNumber", "",
                "fullName", "Blank Mrn " + suffix)).getStatusCode(),
                "blank medicalRecordNumber must return 400");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/patients", token, Map.of(
                "medicalRecordNumber", "MRN-" + suffix + "-BLANK",
                "fullName", "  ")).getStatusCode(),
                "blank fullName must return 400");

        ResponseEntity<Map<String, Object>> created = post("/api/patients", token, fullPatientPayload("-UPD"));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> createdBody = created.getBody();
        assertNotNull(createdBody);
        String patientId = String.valueOf(createdBody.get("id"));
        assertEquals(HttpStatus.BAD_REQUEST, put("/api/patients/" + patientId, token, Map.of(
                "fullName", "")).getStatusCode(),
                "blank fullName on update must return 400");
    }

    @Test
    void invalidPatientDateOfBirthReturns400() {
        String token = login(RECEPTIONIST);
        Map<String, Object> payload = new LinkedHashMap<>(fullPatientPayload("-BADDATE"));
        payload.put("dateOfBirth", "not-a-date");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/patients", token, payload).getStatusCode(),
                "unparseable dateOfBirth must return 400");
    }

    @Test
    void blankAppointmentFieldsReturn400() {
        String token = login(RECEPTIONIST);
        for (String blankedField : List.of("patientId", "professionalId", "scheduledAt", "type", "status")) {
            Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                    "patientId", "synthetic-patient-" + suffix,
                    "professionalId", "synthetic-professional-" + suffix,
                    "scheduledAt", "2031-01-01T09:00:00",
                    "type", "consultation",
                    "status", "scheduled"));
            payload.put(blankedField, "  ");
            assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, payload).getStatusCode(),
                    "blank " + blankedField + " must return 400");
        }
    }

    @Test
    void invalidAppointmentScheduledAtReturns400() {
        String token = login(RECEPTIONIST);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                "patientId", "synthetic-patient-" + suffix,
                "professionalId", "synthetic-professional-" + suffix,
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "missing scheduledAt must return 400");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                "patientId", "synthetic-patient-" + suffix,
                "professionalId", "synthetic-professional-" + suffix,
                "scheduledAt", "not-a-date-time",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "unparseable scheduledAt must return 400");
    }

    @Test
    void invalidAppointmentStatusReturns400() {
        String token = login(RECEPTIONIST);
        for (String invalidStatus : List.of("postponed", "Scheduled")) {
            assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                    "patientId", "synthetic-patient-" + suffix,
                    "professionalId", "synthetic-professional-" + suffix,
                    "scheduledAt", "2031-03-03T11:00:00",
                    "type", "consultation",
                    "status", invalidStatus)).getStatusCode(),
                    "status '" + invalidStatus + "' is outside the lowercase contract and must return 400");
        }
    }

    /**
     * Why: this pins a KNOWN WEAK contract. Today POST /api/appointments
     * persists any non-blank strings as patientId/professionalId without
     * proving the referenced records exist. docs/plan1.md Task 4 intentionally
     * changes this behavior; this test is characterization only and must be
     * updated together with that task, never treated as an endorsement.
     * Task 3 scope ends at the response being a DTO with raw reference
     * strings retained and typed scheduledAt/status validation enforced.
     */
    @Test
    void appointmentCreationStillAcceptsArbitraryNonblankRawReferencesUntilTask4() {
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
        assertEquals(APPOINTMENT_CONTRACT_FIELDS, body.keySet(), "appointment response must match the stable DTO contract exactly");
        assertEquals(fakePatientId, body.get("patientId"));
        assertEquals(fakeProfessionalId, body.get("professionalId"));
        assertEquals("2031-01-01T09:00", body.get("scheduledAt"),
                "scheduledAt must persist as the validated typed value, not the raw request string");
        assertEquals("consultation", body.get("type"));
        assertEquals("scheduled", body.get("status"), "the valid lowercase scheduled status stays accepted");
    }

    @Test
    void appointmentDetailAndListExposeDtoContract() {
        String token = login(RECEPTIONIST);
        ResponseEntity<Map<String, Object>> created = post("/api/appointments", token, Map.of(
                "patientId", "synthetic-patient-" + suffix,
                "professionalId", "synthetic-professional-" + suffix,
                "scheduledAt", "2031-02-02T10:30:00",
                "type", "follow-up",
                "status", "scheduled"));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> createdBody = created.getBody();
        assertNotNull(createdBody);
        String appointmentId = String.valueOf(createdBody.get("id"));

        ResponseEntity<Map<String, Object>> detail = getMap("/api/appointments/" + appointmentId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        Map<String, Object> detailBody = detail.getBody();
        assertNotNull(detailBody);
        assertEquals(APPOINTMENT_CONTRACT_FIELDS, detailBody.keySet(),
                "appointment detail must match the stable DTO contract exactly");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/appointments", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "appointment list must carry a body");
        assertFalse(listBody.isEmpty(), "appointment list must be non-empty");
        for (Map<String, Object> item : listBody) {
            assertEquals(APPOINTMENT_CONTRACT_FIELDS, item.keySet(),
                    "every appointment list item must match the stable DTO contract exactly");
        }
    }

    @Test
    void staffListExposesDtoContractForProfessionalSelection() {
        String token = login(ADMIN_USER);
        ResponseEntity<Map<String, Object>> created = post("/api/staff", token, Map.of(
                "employeeCode", "EMP-" + suffix,
                "fullName", "Dr. Synthetic " + suffix,
                "profession", "cardiology",
                "licenseNumber", "LIC-" + suffix,
                "department", "internal medicine"));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertNotNull(body.get("id"), "staff response must carry its UUID identity");
        assertEquals("Dr. Synthetic " + suffix, body.get("fullName"));

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/staff", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "staff list must carry a body");
        assertFalse(listBody.isEmpty(), "staff list must be non-empty");
        for (Map<String, Object> item : listBody) {
            assertNotNull(item.get("id"), "professional selection needs the staff UUID");
            assertNotNull(item.get("fullName"), "professional selection needs the display name");
            assertNull(item.get("version"), "persistence internals must never leak");
            assertNull(item.get("createdAt"), "persistence internals must never leak");
        }
    }

    @Test
    void staffRoleIsForbiddenFromPatientEndpoints() {
        String token = login(DENIED);
        assertEquals(HttpStatus.FORBIDDEN, getStatus("/api/patients", token).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("/api/patients", token, Map.of(
                "medicalRecordNumber", "MRN-" + suffix + "-DENIED",
                "fullName", "Denied Synthetic " + suffix)).getStatusCode());
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
        List<Map<String, Object>> auditBody = audit.getBody();
        assertNotNull(auditBody, "audit response must carry a body");
        List<Map<String, Object>> events = auditBody.stream()
                .filter(e -> "Patient".equals(e.get("resourceType")) && patientId.equals(e.get("resourceId")))
                .collect(Collectors.toList());
        assertEquals(1, events.size(), "exactly one audit event must exist for the created resource");
        Map<String, Object> event = events.get(0);
        assertEquals("CREATE", event.get("action"));
        assertEquals(RECEPTIONIST, event.get("actor"));
        assertEquals(mrn, event.get("details"));
        assertNotNull(event.get("occurredAt"));
    }

    /** Full synthetic patient payload with a test-local unique MRN/email. */
    private Map<String, Object> fullPatientPayload(String tag) {
        return Map.ofEntries(
                Map.entry("medicalRecordNumber", "MRN-" + suffix + tag),
                Map.entry("fullName", "Test Patient " + tag + " " + suffix),
                Map.entry("dateOfBirth", "2011-02-03"),
                Map.entry("sex", "unspecified"),
                Map.entry("phone", "+10000000002"),
                Map.entry("email", "dto-" + suffix + "@synthetic.test"),
                Map.entry("nationalId", "NID-" + suffix + tag),
                Map.entry("address", "2 Synthetic Street"));
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

    private ResponseEntity<Map<String, Object>> put(String path, String token, Map<String, Object> payload) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(payload, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** Error responses carry a JSON error object, so status checks read the raw body. */
    private ResponseEntity<String> getStatus(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private ResponseEntity<Map<String, Object>> getMap(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
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

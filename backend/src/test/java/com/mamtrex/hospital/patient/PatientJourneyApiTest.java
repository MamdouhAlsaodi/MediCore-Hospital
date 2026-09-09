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
 * (docs/plan1.md) pinned the normalized DTO contracts: Patient Journey
 * routes return immutable response DTOs without persistence internals
 * (version/createdAt/updatedAt), malformed UUID paths are client errors,
 * blank/invalid request values return 400, appointment scheduledAt is a
 * typed ISO LocalDateTime, and appointment status is bound to the explicit
 * lowercase contract scheduled|confirmed|completed|cancelled. Task 4
 * replaces the raw appointment reference contract with verified
 * relationships: patientId/professionalId are typed UUIDs, unknown
 * references return 404, malformed references return 400, only verified
 * references persist an appointment, and only the successful mutation
 * records an Appointment CREATE audit event. Runs against an isolated
 * in-memory H2 database (never the production file store) with disposable
 * synthetic test-only secrets and fabricated record values; no real
 * personal or clinical data is ever used.
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

    /** Stable public Appointment DTO contract; references are verified UUIDs. */
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
        for (String blankedField : List.of("scheduledAt", "type", "status")) {
            Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                    "patientId", UUID.randomUUID().toString(),
                    "professionalId", UUID.randomUUID().toString(),
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
                "patientId", UUID.randomUUID().toString(),
                "professionalId", UUID.randomUUID().toString(),
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "missing scheduledAt must return 400");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                "patientId", UUID.randomUUID().toString(),
                "professionalId", UUID.randomUUID().toString(),
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
                    "patientId", UUID.randomUUID().toString(),
                    "professionalId", UUID.randomUUID().toString(),
                    "scheduledAt", "2031-03-03T11:00:00",
                    "type", "consultation",
                    "status", invalidStatus)).getStatusCode(),
                    "status '" + invalidStatus + "' is outside the lowercase contract and must return 400");
        }
    }

    /**
     * Task 4 (docs/plan1.md): patientId/professionalId are typed UUIDs, so a
     * malformed non-UUID reference value can never reach the workflow and an
     * absent typed reference violates @NotNull — both are client errors (400)
     * handled by the shared GlobalExceptionHandler malformed-body mapping.
     */
    @Test
    void malformedAppointmentReferencesReturn400() {
        String token = login(RECEPTIONIST);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                "patientId", "not-a-uuid-" + suffix,
                "professionalId", UUID.randomUUID().toString(),
                "scheduledAt", "2031-04-04T14:00:00",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "a malformed non-UUID patient reference must return 400");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                "patientId", UUID.randomUUID().toString(),
                "professionalId", "not-a-uuid-" + suffix,
                "scheduledAt", "2031-04-04T14:00:00",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "a malformed non-UUID professional reference must return 400");

        Map<String, Object> missingPatient = baseAppointmentPayload();
        missingPatient.remove("patientId");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, missingPatient).getStatusCode(),
                "an absent patient reference must return 400");

        Map<String, Object> missingProfessional = baseAppointmentPayload();
        missingProfessional.remove("professionalId");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, missingProfessional).getStatusCode(),
                "an absent professional reference must return 400");
    }

    /** A well-formed random UUID that matches no persisted patient is a 404. */
    @Test
    void missingPatientReferenceReturns404() {
        String token = login(RECEPTIONIST);
        assertEquals(HttpStatus.NOT_FOUND, post("/api/appointments", token, Map.of(
                "patientId", UUID.randomUUID().toString(),
                "professionalId", UUID.randomUUID().toString(),
                "scheduledAt", "2031-04-04T14:00:00",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "a valid random UUID for an unknown patient must return 404");
    }

    /** An existing patient plus an unknown professional UUID is a 404. */
    @Test
    void missingProfessionalReferenceReturns404() {
        String token = login(RECEPTIONIST);
        String patientId = createVerifiedPatientId(token, "-PRO");
        assertEquals(HttpStatus.NOT_FOUND, post("/api/appointments", token, Map.of(
                "patientId", patientId,
                "professionalId", UUID.randomUUID().toString(),
                "scheduledAt", "2031-04-04T14:00:00",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "a valid random UUID for an unknown professional must return 404");
    }

    /**
     * Task 4 verified relationships: an existing patient plus an existing
     * StaffMember is the only combination that persists an appointment, and
     * the response keeps the stable six-field contract with the verified
     * references and the canonical typed scheduledAt value.
     */
    @Test
    void verifiedReferencesCreateAppointmentWithStableContract() {
        String token = login(RECEPTIONIST);
        String patientId = createVerifiedPatientId(token, "-VER");
        String professionalId = createVerifiedStaffId("-VER");
        ResponseEntity<Map<String, Object>> created = post("/api/appointments", token, Map.of(
                "patientId", patientId,
                "professionalId", professionalId,
                "scheduledAt", "2031-04-04T14:00:00",
                "type", "consultation",
                "status", "scheduled"));
        assertEquals(HttpStatus.OK, created.getStatusCode(),
                "existing patient and professional must allow appointment creation");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(APPOINTMENT_CONTRACT_FIELDS, body.keySet(),
                "appointment response must match the stable DTO contract exactly");
        assertEquals(patientId, body.get("patientId"), "response must carry the verified patient reference");
        assertEquals(professionalId, body.get("professionalId"), "response must carry the verified professional reference");
        assertEquals("2031-04-04T14:00", body.get("scheduledAt"),
                "scheduledAt must persist as the validated typed value, not the raw request string");
        assertEquals("consultation", body.get("type"));
        assertEquals("scheduled", body.get("status"), "the valid lowercase scheduled status stays accepted");

        String appointmentId = String.valueOf(body.get("id"));
        ResponseEntity<Map<String, Object>> detail = getMap("/api/appointments/" + appointmentId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        Map<String, Object> detailBody = detail.getBody();
        assertNotNull(detailBody);
        assertEquals(APPOINTMENT_CONTRACT_FIELDS, detailBody.keySet(),
                "appointment detail must match the stable DTO contract exactly");
        assertEquals(patientId, detailBody.get("patientId"));
        assertEquals(professionalId, detailBody.get("professionalId"));
    }

    /**
     * Task 4 audit ownership: failed reference validation creates neither an
     * appointment nor an Appointment CREATE audit event, and only the
     * successful mutation records exactly one CREATE event tied to the
     * created appointment.
     */
    @Test
    void failedAppointmentValidationCreatesNeitherAppointmentNorAudit() {
        String adminToken = login(ADMIN_USER);
        long createEventsBefore = countAppointmentCreateEvents(adminToken);

        String token = login(RECEPTIONIST);
        String missingPatientId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.NOT_FOUND, post("/api/appointments", token, Map.of(
                "patientId", missingPatientId,
                "professionalId", UUID.randomUUID().toString(),
                "scheduledAt", "2031-05-05T09:00:00",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "an unknown patient reference must return 404");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/appointments", token, Map.of(
                "patientId", "not-a-uuid-" + suffix,
                "professionalId", UUID.randomUUID().toString(),
                "scheduledAt", "2031-05-05T09:00:00",
                "type", "consultation",
                "status", "scheduled")).getStatusCode(),
                "a malformed patient reference must return 400");

        assertEquals(createEventsBefore, countAppointmentCreateEvents(adminToken),
                "failed validation must not create any Appointment CREATE audit event");
        ResponseEntity<List<Map<String, Object>>> list = getList("/api/appointments", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "appointment list must carry a body");
        assertTrue(listBody.stream().noneMatch(a -> missingPatientId.equals(a.get("patientId"))),
                "failed validation must not persist an appointment for the attempted reference");

        String patientId = createVerifiedPatientId(token, "-AUD2");
        String professionalId = createVerifiedStaffId("-AUD2");
        ResponseEntity<Map<String, Object>> created = post("/api/appointments", token, Map.of(
                "patientId", patientId,
                "professionalId", professionalId,
                "scheduledAt", "2031-06-06T08:15:00",
                "type", "consultation",
                "status", "scheduled"));
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> createdBody = created.getBody();
        assertNotNull(createdBody);
        String appointmentId = String.valueOf(createdBody.get("id"));

        assertEquals(createEventsBefore + 1, countAppointmentCreateEvents(adminToken),
                "exactly one new Appointment CREATE audit event must follow the successful mutation");
        ResponseEntity<List<Map<String, Object>>> audit = getList("/api/audit", adminToken);
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        List<Map<String, Object>> auditBody = audit.getBody();
        assertNotNull(auditBody, "audit response must carry a body");
        List<Map<String, Object>> events = auditBody.stream()
                .filter(e -> "Appointment".equals(e.get("resourceType")) && appointmentId.equals(e.get("resourceId")))
                .collect(Collectors.toList());
        assertEquals(1, events.size(), "exactly one audit event must exist for the created appointment");
        assertEquals("CREATE", events.get(0).get("action"));
        assertEquals(RECEPTIONIST, events.get(0).get("actor"));
        assertNotNull(events.get(0).get("occurredAt"));
    }

    @Test
    void appointmentDetailAndListExposeDtoContract() {
        String token = login(RECEPTIONIST);
        String patientId = createVerifiedPatientId(token, "-LIST");
        String professionalId = createVerifiedStaffId("-LIST");
        ResponseEntity<Map<String, Object>> created = post("/api/appointments", token, Map.of(
                "patientId", patientId,
                "professionalId", professionalId,
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

    /** Creates a verified synthetic patient and returns its UUID string. */
    private String createVerifiedPatientId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = post("/api/patients", token, fullPatientPayload(tag));
        assertEquals(HttpStatus.OK, created.getStatusCode(), "synthetic patient creation must succeed");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        return String.valueOf(body.get("id"));
    }

    /** Creates a verified synthetic StaffMember (ADMIN route) and returns its UUID string. */
    private String createVerifiedStaffId(String tag) {
        ResponseEntity<Map<String, Object>> created = post("/api/staff", login(ADMIN_USER), Map.of(
                "employeeCode", "EMP-" + suffix + tag,
                "fullName", "Dr. Synthetic " + suffix + tag,
                "profession", "cardiology",
                "licenseNumber", "LIC-" + suffix + tag,
                "department", "internal medicine"));
        assertEquals(HttpStatus.OK, created.getStatusCode(), "synthetic staff creation must succeed");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        return String.valueOf(body.get("id"));
    }

    /** Well-formed appointment payload whose references are valid-format UUIDs. */
    private Map<String, Object> baseAppointmentPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", UUID.randomUUID().toString());
        payload.put("professionalId", UUID.randomUUID().toString());
        payload.put("scheduledAt", "2031-04-04T14:00:00");
        payload.put("type", "consultation");
        payload.put("status", "scheduled");
        return payload;
    }

    /** Counts Appointment CREATE audit events through the ADMIN audit route. */
    private long countAppointmentCreateEvents(String adminToken) {
        ResponseEntity<List<Map<String, Object>>> audit = getList("/api/audit", adminToken);
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        List<Map<String, Object>> auditBody = audit.getBody();
        assertNotNull(auditBody, "audit response must carry a body");
        return auditBody.stream()
                .filter(e -> "Appointment".equals(e.get("resourceType")))
                .filter(e -> "CREATE".equals(e.get("action")))
                .count();
    }

    /** Full synthetic patient payload with a test-local unique MRN/email. */
    private Map<String, Object> fullPatientPayload(String tag) {
        return Map.ofEntries(
                Map.entry("medicalRecordNumber", "MRN-" + suffix + tag),
                Map.entry("fullName", "Test Patient " + tag + " " + suffix),
                Map.entry("dateOfBirth", "2011-02-03"),
                Map.entry("sex", "unspecified"),
                Map.entry("phone", "+10000000002"),
                Map.entry("email", "dto-" + suffix + tag + "@synthetic.test"),
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

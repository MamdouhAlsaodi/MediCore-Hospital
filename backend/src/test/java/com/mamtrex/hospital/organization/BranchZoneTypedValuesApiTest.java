package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.TestRuntimeSecrets;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.staff.StaffAvailabilityRepository;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Branch-zone typed workflow evidence over real HTTP and an isolated H2
 * store (plan Task 4; FR-012, FR-015).
 *
 * Proves the Phase 3 wire contract survives the typed migration: a
 * branch-local input string round-trips unchanged through typed storage on
 * a UTC branch and on a non-UTC branch; the stored instants are the
 * zone-correct unambiguous values; DST gaps are rejected with 400; DST
 * overlaps resolve to the documented earlier offset; admissions,
 * emergency visits, and invoices round-trip with their exact Phase 3
 * string shapes (amounts canonicalized to scale-2 plain strings).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:branch-zone-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class BranchZoneTypedValuesApiTest {

    private static final String TEST_JWT_SECRET = TestRuntimeSecrets.jwtSecret();
    private static final String TEST_ACCOUNT_PASSWORD = TestRuntimeSecrets.accountPassword();

    private static final String ADMIN = "zone-admin";
    private static final String TEST_ORG_CODE = "ZONE-TYPED-ORG";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("HOSPITAL_ADMIN_PASSWORD", () -> TEST_ACCOUNT_PASSWORD);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    PatientRepository patients;

    @Autowired
    StaffMemberRepository staffMembers;

    @Autowired
    StaffAvailabilityRepository staffAvailability;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    AdmissionRepository admissions;

    @Autowired
    EmergencyVisitRepository emergencyVisits;

    @Autowired
    InvoiceRepository invoices;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private HospitalOrganization org;
    private Branch utcBranch;
    private Branch nyBranch;

    @BeforeEach
    void seed() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Zone Typed Demo Organization")));
        // An ORGANIZATION-scope login resolves its active branch server-side,
        // so at least one active branch must exist before the first login.
        utcBranch = branches.findByOrganizationIdAndCode(org.getId(), suffix + "-utc")
                .orElseGet(() -> branches.save(new Branch(org, suffix + "-utc",
                        "Demo UTC Branch", "1 Demo Campus")));
        nyBranch = branches.findByOrganizationIdAndCode(org.getId(), suffix + "-ny")
                .orElseGet(() -> branches.save(new Branch(org, suffix + "-ny",
                        "Demo NY Branch", "2 Demo Campus",
                        java.time.ZoneId.of("America/New_York"))));
        // Login requires a valid enabled acting assignment (no fallback).
        UserAccount admin = accounts.findByUsername(ADMIN).orElseThrow();
        if (assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(admin.getId()).isEmpty()) {
            assignments.save(ActingAssignment.organization(admin, org, Role.ADMIN));
        }
    }

    // ------------------------------------------------------------- tests

    /** Ordinary round trip on a UTC branch: the wire string is byte-identical. */
    @Test
    void appointmentRoundTripsOnUtcBranch() {
        String token = tokenActingOn(utcBranch);
        UUID patientId = createPatient(token, utcBranch);
        UUID professionalId = createProfessionalWithAvailability(token, utcBranch);

        Map<String, Object> created = createAppointment(token, patientId, professionalId,
                "2031-06-01T09:30", 30, utcBranch);
        assertEquals("2031-06-01T09:30", created.get("scheduledAt"));
        assertEquals("2031-06-01T10:00", created.get("endsAt"));
    }

    /** A non-UTC branch stores the zone-correct instant and renders the same local string. */
    @Test
    void appointmentStoresZoneCorrectInstantAndRoundTripsOnNonUtcBranch() {
        String token = tokenActingOn(nyBranch);
        UUID patientId = createPatient(token, nyBranch);
        UUID professionalId = createProfessionalWithAvailability(token, nyBranch);

        Map<String, Object> created = createAppointment(token, patientId, professionalId,
                "2031-06-01T12:00", 30, nyBranch);
        assertEquals("2031-06-01T12:00", created.get("scheduledAt"), "branch-local rendering round-trips");
        assertEquals("2031-06-01T12:30", created.get("endsAt"));

        // Storage is the unambiguous UTC instant (12:00 New York summer = 16:00Z).
        var stored = appointments.findById(UUID.fromString(created.get("id").toString())).orElseThrow();
        assertEquals(Instant.parse("2031-06-01T16:00:00Z"), stored.getScheduledAt(),
                "storage must be the unambiguous instant, not a naive string");
        assertEquals(Instant.parse("2031-06-01T16:30:00Z"), stored.getEndsAt());
    }

    /** A nonexistent DST-gap local time is rejected with the shared 400 shape. */
    @Test
    void dstGapAppointmentTimeIsRejectedWith400() {
        String token = tokenActingOn(nyBranch);
        UUID patientId = createPatient(token, nyBranch);
        UUID professionalId = createProfessionalWithAvailability(token, nyBranch);

        // Availability covering 2031-03-09, and a create inside the spring-forward gap.
        Map<String, Object> body = createAppointmentRaw(token, patientId, professionalId,
                "2031-03-09T02:30", 30);
        assertEquals(HttpStatus.BAD_REQUEST, statusCode(body), "a DST-gap time must fail with 400");
        assertNotNull(body.get("body"));
    }

    /** An ambiguous DST-overlap local time resolves to the documented earlier offset. */
    @Test
    void dstOverlapResolvesToEarlierOffset() {
        String token = tokenActingOn(nyBranch);
        UUID patientId = createPatient(token, nyBranch);
        UUID professionalId = createProfessionalWithAvailability(token, nyBranch);

        Map<String, Object> created = createAppointment(token, patientId, professionalId,
                "2031-11-02T01:30", 30, nyBranch);
        assertEquals("2031-11-02T01:30", created.get("scheduledAt"), "the ambiguous local time renders identically");
        var stored = appointments.findById(UUID.fromString(created.get("id").toString())).orElseThrow();
        assertEquals(Instant.parse("2031-11-02T05:30:00Z"), stored.getScheduledAt(),
                "the overlap must resolve to the earlier (EDT) offset");
    }

    /** Admissions migrate to typed instants with an identical wire shape. */
    @Test
    void admissionRoundTripsWithTypedStorage() {
        String token = tokenActingOn(nyBranch);
        UUID patientId = createPatient(token, nyBranch);
        String admissionId = createAdmission(token, patientId, "2031-02-03T10:00");
        Map<String, Object> body = getJson("/api/admissions/" + admissionId, token);
        assertEquals("2031-02-03T10:00", body.get("admittedAt"), "branch-local rendering round-trips");
        var stored = admissions.findById(UUID.fromString(admissionId)).orElseThrow();
        assertEquals(Instant.parse("2031-02-03T15:00:00Z"), stored.getAdmittedAt(),
                "storage must be the zone-correct instant");
    }

    /** Emergency visits migrate to typed instants with an identical wire shape. */
    @Test
    void emergencyVisitRoundTripsWithTypedStorage() {
        String token = tokenActingOn(nyBranch);
        UUID patientId = createPatient(token, nyBranch);
        ResponseEntity<Map<String, Object>> response = postJson("/api/emergency-visits", token, Map.of(
                "patientId", patientId.toString(),
                "arrivalAt", "2031-04-05T14:15",
                "triageLevel", "3",
                "chiefComplaint", "Demo typed storage intake"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);
        assertEquals("2031-04-05T14:15", created.get("arrivalAt"));
        var stored = emergencyVisits.findById(UUID.fromString(created.get("id").toString())).orElseThrow();
        assertEquals(Instant.parse("2031-04-05T18:15:00Z"), stored.getArrivalAt());
    }

    /** Invoices persist exact numeric values and echo canonical scale-2 plain strings. */
    @Test
    void invoiceAmountIsExactNumericWithCanonicalPlainString() {
        String token = tokenActingOn(utcBranch);
        UUID patientId = createPatient(token, utcBranch);
        ResponseEntity<Map<String, Object>> response = postJson("/api/invoices", token, Map.of(
                "patientId", patientId.toString(),
                "invoiceNumber", "INV-ZONE-" + suffix,
                "amount", "1234.5",
                "currency", "USD"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);
        assertEquals("1234.50", created.get("amount"), "the amount is canonical scale-2 plain string");
        assertEquals(String.class, created.get("amount").getClass(), "the wire keeps the string shape");
    }

    /** An invalid IANA zone is refused at branch creation with the shared 400 shape. */
    @Test
    void invalidBranchZoneIsRefused() {
        ResponseEntity<Map<String, Object>> response = postJson("/api/branches", login(), Map.of(
                "code", suffix + "-badzone",
                "name", "Demo Bad Zone Branch",
                "locationLabel", "3 Demo Campus",
                "timeZone", "Mars/Olympus"));
        assertEquals(HttpStatus.BAD_REQUEST, statusCode(response),
                "an invalid IANA zone must be refused");
    }

    // --------------------------------------------------------- helpers

    private Branch createBranch(String code, String timeZone) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("code", code);
        payload.put("name", "Demo Branch " + code);
        payload.put("locationLabel", "1 Demo Campus");
        if (timeZone != null) {
            payload.put("timeZone", timeZone);
        }
        ResponseEntity<Map<String, Object>> response = postJson("/api/branches", login(), payload);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "branch creation must succeed");
        assertNotNull(response.getBody());
        return branches.findById(UUID.fromString(response.getBody().get("id").toString())).orElseThrow();
    }

    private String login() {
        ResponseEntity<Map<String, Object>> response = postJson("/api/auth/login", null, Map.of(
                "username", ADMIN, "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private String tokenActingOn(Branch branch) {
        UUID assignmentId = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(
                        accounts.findByUsername(ADMIN).orElseThrow().getId())
                .stream().filter(a -> a.getRole() == Role.ADMIN).findFirst().orElseThrow().getId();
        ResponseEntity<Map<String, Object>> switched = postJson("/api/auth/context", login(), Map.of(
                "assignmentId", assignmentId.toString(),
                "branchId", branch.getId().toString()));
        assertEquals(HttpStatus.OK, switched.getStatusCode());
        return String.valueOf(switched.getBody().get("accessToken"));
    }

    private UUID createPatient(String token, Branch branch) {
        ResponseEntity<Map<String, Object>> response = postJson("/api/patients", token, Map.of(
                "medicalRecordNumber", "ZONE-" + suffix + "-" + branch.getCode(),
                "fullName", "Demo Zone Patient",
                "dateOfBirth", "1991-02-03"));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "patient creation must succeed");
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private UUID createProfessionalWithAvailability(String token, Branch branch) {
        ResponseEntity<Map<String, Object>> response = postJson("/api/staff", token, Map.of(
                "employeeCode", "ZONE-STAFF-" + suffix + "-" + branch.getCode(),
                "fullName", "Demo Zone Professional",
                "profession", "doctor",
                "licenseNumber", "DEMO-LIC-" + suffix + "-" + branch.getCode(),
                "department", "Demo Internal Medicine"));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "staff creation must succeed");
        UUID professionalId = UUID.fromString(response.getBody().get("id").toString());
        // Availability covering both the DST-gap day and the overlap day.
        ResponseEntity<Map<String, Object>> availabilityResponse = postJson(
                "/api/staff/" + professionalId + "/availability", token, Map.of(
                        "startsAt", "2031-03-09T00:00",
                        "endsAt", "2031-12-31T23:59"));
        assertEquals(HttpStatus.OK, availabilityResponse.getStatusCode(), "availability creation must succeed");
        return professionalId;
    }

    private Map<String, Object> createAppointment(String token, UUID patientId, UUID professionalId,
                                                  String scheduledAt, int durationMinutes, Branch branch) {
        Map<String, Object> raw = createAppointmentRaw(token, patientId, professionalId, scheduledAt, durationMinutes);
        assertEquals(HttpStatus.OK.value(), raw.get("status"),
                "appointment creation must succeed: " + raw.get("body"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) raw.get("body");
        assertNotNull(body);
        return body;
    }

    private Map<String, Object> createAppointmentRaw(String token, UUID patientId, UUID professionalId,
                                                     String scheduledAt, int durationMinutes) {
        return postJsonRaw("/api/appointments", token, Map.of(
                "patientId", patientId.toString(),
                "professionalId", professionalId.toString(),
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "type", "consultation",
                "status", "scheduled"));
    }

    private String createAdmission(String token, UUID patientId, String admittedAt) {
        ResponseEntity<Map<String, Object>> response = postJson("/api/admissions", token, Map.of(
                "patientId", patientId.toString(),
                "admittedAt", admittedAt,
                "reason", "Demo typed storage intake"));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "admission creation must succeed");
        return response.getBody().get("id").toString();
    }

    private HttpStatus statusCode(Map<String, Object> raw) {
        return HttpStatus.valueOf((Integer) raw.get("status"));
    }

    private HttpStatus statusCode(ResponseEntity<Map<String, Object>> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /** Posts JSON and returns the raw status + body map for negative assertions. */
    private Map<String, Object> postJsonRaw(String path, String token, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(payload, headers), MAP);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("status", response.getStatusCode().value());
        out.put("body", response.getBody());
        return out;
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, String token, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
    }

    private Map<String, Object> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map<String, Object>> response = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers), MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }
}

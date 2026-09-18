package com.mamtrex.hospital.infrastructure;

import com.mamtrex.hospital.TestRuntimeSecrets;
import com.mamtrex.hospital.audit.AuditEventRepository;
import com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-PostgreSQL concurrency evidence (plan Task 5, steps 5-6; FR-004,
 * SC-003). Two genuine HTTP clients race the same command simultaneously
 * (barrier-synchronized threads, no sleeps): a same-bed admission create
 * and an overlapping appointment create. Every iteration must end with
 * EXACTLY one legal winner (2xx), one typed 409 loser, no partial rows,
 * and exactly one success audit event — and the races repeat enough times
 * to expose timing-sensitive failures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PostgresConcurrencyIntegrationTest {

    private static final String TEST_JWT_SECRET = TestRuntimeSecrets.jwtSecret();
    private static final String TEST_ACCOUNT_PASSWORD = TestRuntimeSecrets.accountPassword();

    private static final String ADMIN = "race-admin";
    private static final String TEST_ORG_CODE = "RACE-ORG";

    private static final int RACE_ITERATIONS = 5;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired
    TestRestTemplate rest;

    @Autowired
    com.mamtrex.hospital.auth.UserAccountRepository accounts;

    @Autowired
    com.mamtrex.hospital.auth.ActingAssignmentRepository assignments;

    @Autowired
    com.mamtrex.hospital.organization.HospitalOrganizationRepository organizations;

    @Autowired
    com.mamtrex.hospital.organization.BranchRepository branches;

    @Autowired
    com.mamtrex.hospital.organization.HospitalFacilityRepository hospitals;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    AdmissionBedAssignmentRepository bedAssignments;

    @Autowired
    AppointmentRepository appointments;

    private String token;
    private UUID branchId;

    @DynamicPropertySource
    static void reviewDataSource(DynamicPropertyRegistry registry) {
        var db = PostgresContainerSupport.newIsolatedDatabase();
        org.flywaydb.core.Flyway.configure()
                .locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password())
                .load()
                .migrate();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
        registry.add("hospital.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("HOSPITAL_ADMIN_PASSWORD", () -> TEST_ACCOUNT_PASSWORD);
    }

    @BeforeEach
    void seed() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new com.mamtrex.hospital.auth.UserAccount(ADMIN,
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                            .encode(TEST_ACCOUNT_PASSWORD),
                    java.util.Set.of(com.mamtrex.hospital.auth.Role.ADMIN)));
        }
        var org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new com.mamtrex.hospital.organization.HospitalOrganization(
                        TEST_ORG_CODE, "Race Demo Organization")));
        var fixtureHospital = com.mamtrex.hospital.organization.FixtureHospitals.ensureHospital(hospitals, org);
        var branch = branches.findByHospitalIdAndCode(fixtureHospital.getId(), "RACE-BR-001")
                .orElseGet(() -> branches.save(new com.mamtrex.hospital.organization.Branch(
                        fixtureHospital, "RACE-BR-001", "Demo Race Branch", "1 Race Way")));
        var admin = accounts.findByUsername(ADMIN).orElseThrow();
        if (assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(admin.getId()).isEmpty()) {
            assignments.save(com.mamtrex.hospital.auth.ActingAssignment.organization(admin, org,
                    com.mamtrex.hospital.auth.Role.ADMIN));
        }
        this.branchId = branch.getId();
        this.token = login();
    }

    /** Same-bed admission create race: one winner, one typed 409, no partial state. */
    @Test
    void concurrentSameBedAdmissionCreatesHaveExactlyOneWinner() throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            final int roundNo = round;
            UUID patientId = createPatient("RACE-A" + round);
            UUID bedId = createBed("R", String.valueOf(100 + round));
            long auditsBefore = auditEvents.count();
            long assignmentsBefore = bedAssignments.count();

            AtomicInteger winner = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            race(() -> post("/api/admissions", Map.of(
                    "patientId", patientId.toString(),
                    "admittedAt", "2031-05-0" + ((roundNo % 9) + 1) + "T08:00",
                    "reason", "Race round " + roundNo + " admission A",
                    "bedId", bedId.toString())),
            () -> post("/api/admissions", Map.of(
                    "patientId", patientId.toString(),
                    "admittedAt", "2031-05-0" + ((roundNo % 9) + 1) + "T08:00",
                    "reason", "Race round " + roundNo + " admission B",
                    "bedId", bedId.toString())),
            status -> {
                if (status.is2xxSuccessful()) winner.incrementAndGet();
                else if (status.value() == 409) conflict.incrementAndGet();
            });

            assertEquals(1, winner.get(), "round " + roundNo + ": exactly one legal admission winner");
            assertEquals(1, conflict.get(), "round " + roundNo + ": the loser must receive the typed 409");
            assertEquals(assignmentsBefore + 1, bedAssignments.count(),
                    "round " + roundNo + ": exactly one live assignment row may persist");
            assertEquals(auditsBefore + 1, auditEvents.count(),
                    "round " + roundNo + ": exactly one success audit event (no false winner event)");
        }
    }

    /** Overlapping appointment create race: one winner, one typed 409, no partial state. */
    @Test
    void concurrentOverlappingAppointmentCreatesHaveExactlyOneWinner() throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            final int roundNo = round;
            UUID patientId = createPatient("RACE-P" + round);
            UUID professionalId = createProfessionalWithAvailability("RACE-PRO" + round);
            long auditsBefore = auditEvents.count();

            AtomicInteger winner = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            race(() -> post("/api/appointments", appointmentPayload(patientId, professionalId, "09:30")),
            () -> post("/api/appointments", appointmentPayload(patientId, professionalId, "09:30")),
            status -> {
                if (status.is2xxSuccessful()) winner.incrementAndGet();
                else if (status.value() == 409) conflict.incrementAndGet();
            });

            assertEquals(1, winner.get(), "round " + roundNo + ": exactly one legal appointment winner");
            assertEquals(1, conflict.get(), "round " + roundNo + ": the loser must receive the typed 409");
            assertEquals(auditsBefore + 1, auditEvents.count(),
                    "round " + roundNo + ": exactly one success audit event");
            assertEquals(round, appointments.count(),
                    "round " + roundNo + ": no partial or duplicate appointment rows persist");
        }
    }

    // --------------------------------------------------------- helpers

    private Map<String, Object> appointmentPayload(UUID patientId, UUID professionalId, String time) {
        return Map.of(
                "patientId", patientId.toString(),
                "professionalId", professionalId.toString(),
                "scheduledAt", "2031-07-01T" + time,
                "durationMinutes", 30,
                "type", "consultation",
                "status", "scheduled");
    }

    /**
     * Starts two real HTTP calls at the same barrier instant and folds both
     * responses into the classifier. No sleeps: the serialization comes
     * from the database, not from timing.
     */
    private void race(Callable<ResponseEntity<Map<String, Object>>> first,
                      Callable<ResponseEntity<Map<String, Object>>> second,
                      java.util.function.Consumer<org.springframework.http.HttpStatusCode> classify)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> a = pool.submit(() -> {
                barrier.await();
                classify.accept(first.call().getStatusCode());
                return null;
            });
            Future<?> b = pool.submit(() -> {
                barrier.await();
                classify.accept(second.call().getStatusCode());
                return null;
            });
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
    }

    private String login() {
        ResponseEntity<Map<String, Object>> response = post("/api/auth/login",
                Map.of("username", ADMIN, "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(200, response.getStatusCode().value(), "the synthetic account must log in");
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private UUID createPatient(String mrn) {
        ResponseEntity<Map<String, Object>> response = post("/api/patients", Map.of(
                "medicalRecordNumber", mrn + "-" + UUID.randomUUID().toString().substring(0, 8),
                "fullName", "Demo Race Patient",
                "dateOfBirth", "1990-01-01"));
        assertEquals(200, response.getStatusCode().value(), "patient creation must succeed");
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private UUID createBed(String ward, String number) {
        ResponseEntity<Map<String, Object>> response = post("/api/beds", Map.of(
                "ward", ward, "room", "1", "bedNumber", number));
        assertEquals(200, response.getStatusCode().value(), "bed creation must succeed");
        return UUID.fromString(response.getBody().get("id").toString());
    }

    private UUID createProfessionalWithAvailability(String employeeCode) {
        ResponseEntity<Map<String, Object>> response = post("/api/staff", Map.of(
                "employeeCode", employeeCode + "-" + UUID.randomUUID().toString().substring(0, 8),
                "fullName", "Demo Race Professional",
                "profession", "doctor",
                "licenseNumber", "LIC-" + UUID.randomUUID().toString().substring(0, 8),
                "department", "Demo Internal Medicine"));
        assertEquals(200, response.getStatusCode().value(), "staff creation must succeed");
        UUID professionalId = UUID.fromString(response.getBody().get("id").toString());
        ResponseEntity<Map<String, Object>> availability = post(
                "/api/staff/" + professionalId + "/availability", Map.of(
                        "startsAt", "2031-07-01T08:00",
                        "endsAt", "2031-07-01T12:00"));
        assertEquals(200, availability.getStatusCode().value(), "availability creation must succeed");
        return professionalId;
    }
}

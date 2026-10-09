package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.TestRuntimeSecrets;
import com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository;
import com.mamtrex.hospital.audit.AuditEvent;
import com.mamtrex.hospital.audit.AuditEventRepository;
import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.infrastructure.PostgresContainerSupport;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientAccessSource;
import com.mamtrex.hospital.patient.PatientAccessStatus;
import com.mamtrex.hospital.patient.PatientHospitalAccess;
import com.mamtrex.hospital.patient.PatientHospitalAccessRepository;
import com.mamtrex.hospital.patient.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 5 US4 real-PostgreSQL concurrency evidence (tasks T099/T100, FR-017,
 * SC-005). Barrier-synchronized genuine HTTP clients race the guarded
 * transfer commands against a disposable PostgreSQL database migrated by
 * V1..V7 (nothing mocked, no shared database touched). Every loser MUST
 * receive the typed 409, zero partial rows may survive, and zero false
 * success audits may exist — asserted across REPEAT_ITERATIONS repeats to
 * expose timing-sensitive interleavings:
 *
 * <ul>
 *   <li>two accepts of the SAME destination bed: exactly one winner, one
 *       409, one ACTIVE reservation, one ACCEPT audit;</li>
 *   <li>the same idempotency key racing itself: both responses agree and
 *       exactly one mutation/audit exists;</li>
 *   <li>two concurrent completions: exactly one terminal winner, one 409,
 *       one destination admission, reservation CONSUMED once.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class TransferConcurrencyIntegrationTest {

    private static final String TEST_JWT_SECRET = TestRuntimeSecrets.jwtSecret();
    private static final String TEST_ACCOUNT_PASSWORD = TestRuntimeSecrets.accountPassword();

    private static final String ORG_CODE = "XFER-RACE-ORG";
    private static final String HOSPITAL_A_CODE = "XFER-RACE-A";
    private static final String HOSPITAL_B_CODE = "XFER-RACE-B";

    private static final int RACE_ITERATIONS = 5;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate rest;
    @Autowired UserAccountRepository accounts;
    @Autowired ActingAssignmentRepository assignments;
    @Autowired HospitalOrganizationRepository organizations;
    @Autowired HospitalFacilityRepository hospitals;
    @Autowired BranchRepository branches;
    @Autowired PatientRepository patients;
    @Autowired PatientHospitalAccessRepository accessGrants;
    @Autowired TransferRepository transfers;
    @Autowired TransferBedReservationRepository reservations;
    @Autowired com.mamtrex.hospital.bed.BedRepository beds;
    @Autowired com.mamtrex.hospital.admission.AdmissionRepository admissions;
    @Autowired AdmissionBedAssignmentRepository admissionBedAssignments;
    @Autowired AuditEventRepository auditEvents;

    private String srcDoctor;
    private String dstDoctorA;
    private String dstDoctorB;

    private UUID orgId;
    private UUID hospitalAId;
    private UUID destBranchId;

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
        // Idempotent: the shared Spring context re-runs this for every test.
        srcDoctor = seedUser("xfer-race-src", Role.DOCTOR, HOSPITAL_A_CODE, "RA");
        dstDoctorA = seedUser("xfer-race-dst-a", Role.DOCTOR, HOSPITAL_B_CODE, "RB");
        dstDoctorB = seedUser("xfer-race-dst-b", Role.DOCTOR, HOSPITAL_B_CODE, "RB");

        var org = organizations.findByCode(ORG_CODE).orElseThrow();
        orgId = org.getId();
        var hospitalA = hospitals.findByOrganizationIdAndCode(orgId, HOSPITAL_A_CODE).orElseThrow();
        hospitalAId = hospitalA.getId();
        destBranchId = branches.findByHospitalIdAndCode(
                hospitals.findByOrganizationIdAndCode(orgId, HOSPITAL_B_CODE).orElseThrow().getId(), "RB")
                .orElseThrow().getId();

        UUID patientId;
        var existing = patients.findByMedicalRecordNumber("MRN-XFER-RACE");
        if (existing.isEmpty()) {
            patientId = patients.save(new Patient(
                    branches.findByHospitalIdAndCode(hospitalAId, "RA").orElseThrow(),
                    "MRN-XFER-RACE", "Race Patient", null, null, null, null, null, null)).getId();
        } else {
            patientId = existing.orElseThrow().getId();
        }
        if (!accessGrants.existsByPatientIdAndHospitalIdAndStatus(patientId, hospitalAId,
                PatientAccessStatus.ACTIVE)) {
            accessGrants.save(new PatientHospitalAccess(patients.findById(patientId).orElseThrow(),
                    hospitalA, PatientAccessSource.LOCAL_REGISTRATION, null));
        }
    }

    /** Exactly one winner reserves the raced bed; the loser gets 409; no partial rows or false audits. */
    @Test
    void concurrentAcceptsOfTheSameBedHaveExactlyOneWinner() throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            UUID bedId = freshBed(round);
            final UUID transferA = createTransfer("race-acc-a-" + round);
            final UUID transferB = createTransfer("race-acc-b-" + round);
            final int roundTag = round;
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            race(
                    () -> accept(dstDoctorA, transferA, bedId, "race-acc-key-a-" + roundTag),
                    () -> accept(dstDoctorB, transferB, bedId, "race-acc-key-b-" + roundTag),
                    status -> {
                        if (status.is2xxSuccessful()) winners.incrementAndGet();
                        else if (status.value() == 409) conflicts.incrementAndGet();
                        else throw new AssertionError("unexpected race status: " + status);
                    });
            assertEquals(1, winners.get(), "exactly one accept may win the bed race");
            assertEquals(1, conflicts.get(), "every loser must receive the typed 409");
            // zero partial rows: exactly one ACTIVE reservation on the raced bed
            var rows = reservations.findAll().stream()
                    .filter(r -> bedId.equals(r.getBedId())).toList();
            assertEquals(1, rows.size(), "exactly one reservation row may exist for the raced bed");
            assertEquals(ReservationStatus.ACTIVE, rows.iterator().next().getStatus());
            // the loser stays REQUESTED
            UUID loser = winners.get() > 0 && statusOf(transferA).equals("ACCEPTED") ? transferB : transferA;
            assertEquals("REQUESTED", statusOf(loser));
            // zero false success audits: exactly one ACCEPT audit for the raced bed's transfer
            long acceptAudits = auditEvents.findAll().stream()
                    .filter(e -> "TRANSFER_ACCEPT".equals(e.getAction()))
                    .filter(e -> Set.of(transferA, transferB).contains(e.getTransferId()))
                    .count();
            assertEquals(1, acceptAudits, "exactly one success audit may exist across both racers");
        }
    }

    /** The same idempotency key racing itself produces ONE mutation and agreeing responses. */
    @Test
    void concurrentSameKeyAcceptsProduceOneMutation() throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            final UUID bedId = freshBed(100 + round);
            final UUID transfer = createTransfer("race-idem-" + round);
            final int idemRound = round;
            AtomicInteger successes = new AtomicInteger();
            race(
                    () -> accept(dstDoctorA, transfer, bedId, "race-idem-key-" + idemRound),
                    () -> accept(dstDoctorA, transfer, bedId, "race-idem-key-" + idemRound),
                    status -> {
                        if (status.is2xxSuccessful()) successes.incrementAndGet();
                        else throw new AssertionError("unexpected replay-race status: " + status);
                    });
            assertEquals(2, successes.get(), "both same-key callers must succeed");
            assertEquals(1, reservations.findByTransferId(transfer).stream().count(),
                    "exactly one reservation may exist");
            long acceptAudits = auditEvents.findAll().stream()
                    .filter(e -> "TRANSFER_ACCEPT".equals(e.getAction()) && transfer.equals(e.getTransferId()))
                    .count();
            assertEquals(1, acceptAudits, "a replayed accept must not write a second success audit");
        }
    }

    /** Concurrent completions: exactly one terminal winner, one 409, one destination admission. */
    @Test
    void concurrentCompletionsHaveExactlyOneWinner() throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            UUID bedId = freshBed(200 + round);
            final UUID transfer = createTransfer("race-cmp-" + round);
            UUID patientId = transferPatient(transfer);
            Set<UUID> previousAdmissionIds = admissions.findByBranchId(destBranchId).stream()
                    .filter(a -> patientId.toString().equals(a.getPatientId()))
                    .map(a -> a.getId()).collect(java.util.stream.Collectors.toSet());
            final int cmpRound = round;
            assertEquals(200, post(dstDoctorA, "/api/transfers/" + transfer + "/accept",
                    "race-cmp-acc-" + cmpRound,
                    Map.of("destinationBranchId", destBranchId.toString(),
                            "destinationBedId", bedId.toString(), "expectedVersion", 0),
                    true).getStatusCode().value());
            assertEquals(200, post(srcDoctor, "/api/transfers/" + transfer + "/start-transit",
                    "race-cmp-tr-" + cmpRound, Map.of(), true).getStatusCode().value());
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            race(
                    () -> post(dstDoctorA, "/api/transfers/" + transfer + "/complete",
                            "race-cmp-key-a-" + cmpRound, Map.of(), true),
                    () -> post(dstDoctorB, "/api/transfers/" + transfer + "/complete",
                            "race-cmp-key-b-" + cmpRound, Map.of(), false),
                    status -> {
                        if (status.is2xxSuccessful()) winners.incrementAndGet();
                        else if (status.value() == 409) conflicts.incrementAndGet();
                        else throw new AssertionError("unexpected completion race status: " + status);
                    });
            assertEquals(1, winners.get(), "exactly one completion may win");
            assertEquals(1, conflicts.get(), "the losing completion must receive the typed 409");
            assertEquals("COMPLETED", statusOf(transfer));
            assertEquals(ReservationStatus.CONSUMED,
                    reservations.findByTransferId(transfer).orElseThrow().getStatus());
            var newAdmissions = admissions.findByBranchId(destBranchId).stream()
                    .filter(a -> patientId.toString().equals(a.getPatientId()))
                    .filter(a -> !previousAdmissionIds.contains(a.getId())).toList();
            assertEquals(1, newAdmissions.size(), "exactly one new destination admission per completion race");
            assertEquals(bedId, admissionBedAssignments.findByAdmissionId(newAdmissions.get(0).getId())
                    .orElseThrow(() -> new AssertionError("new admission must hold the reserved bed"))
                    .getBedId(), "the sole new admission must be assigned exactly the reserved bed");
            long completeAudits = auditEvents.findAll().stream()
                    .filter(e -> "TRANSFER_COMPLETE".equals(e.getAction()) && transfer.equals(e.getTransferId()))
                    .count();
            assertEquals(1, completeAudits, "exactly one completion audit may exist");
        }
    }

    /** Same-key create races must agree on identity without duplicating rows or audits. */
    @Test
    void concurrentSameKeyCreatesProduceOneTransfer() throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            var sourceBranch = branches.findByHospitalIdAndCode(hospitalAId, "RA").orElseThrow();
            var patient = patients.findByMedicalRecordNumber("MRN-XFER-RACE").orElseThrow();
            var admission = admissions.save(new com.mamtrex.hospital.admission.Admission(
                    sourceBranch.getId(), patient.getId().toString(), Instant.now(), "race-create"));
            UUID destinationHospital = hospitals.findByOrganizationIdAndCode(orgId, HOSPITAL_B_CODE)
                    .orElseThrow().getId();
            Map<String, Object> payload = Map.of("patientId", patient.getId().toString(),
                    "sourceAdmissionId", admission.getId().toString(),
                    "destinationHospitalId", destinationHospital.toString(), "reasonCode", "BED_SHORTAGE");
            String key = "race-create-same-key-" + round;
            var responses = raceResponses(
                    () -> post(srcDoctor, "/api/transfers", key, payload, false),
                    () -> post(srcDoctor, "/api/transfers", key, payload, false));
            for (var response : responses) {
                assertEquals(201, response.getStatusCode().value(), "same-key create must replay original status");
            }
            UUID transferId = UUID.fromString(String.valueOf(responses.get(0).getBody().get("id")));
            assertEquals(transferId.toString(), String.valueOf(responses.get(1).getBody().get("id")),
                    "both callers must see the same transfer identity");
            assertEquals(1, transfers.findAll().stream()
                    .filter(t -> admission.getId().equals(t.getSourceAdmissionId())).count(),
                    "the shared source admission must produce only one transfer");
            assertEquals(1, auditEvents.findAll().stream()
                    .filter(e -> "TRANSFER_REQUEST".equals(e.getAction()) && transferId.equals(e.getTransferId()))
                    .count(), "the create race must write only one success audit");
        }
    }

    // ------------------------------------------------------------- helpers

    private UUID transferPatient(UUID transferId) {
        return transfers.findById(transferId).orElseThrow().getPatientId();
    }

    private String statusOf(UUID transferId) {
        return transfers.findById(transferId).orElseThrow().getStatus().name();
    }

    private UUID freshBed(int tag) {
        var branch = branches.findById(destBranchId).orElseThrow();
        String unique = "RACE-" + tag + "-" + UUID.randomUUID();
        return beds.save(new com.mamtrex.hospital.bed.Bed(branch, "RW", "R" + tag, unique)).getId();
    }

    private UUID createTransfer(String keySuffix) {
        var branchA = branches.findByHospitalIdAndCode(hospitalAId, "RA").orElseThrow();
        var patient = patients.findByMedicalRecordNumber("MRN-XFER-RACE").orElseThrow();
        var admission = admissions.save(new com.mamtrex.hospital.admission.Admission(
                branchA.getId(), patient.getId().toString(), Instant.now(), "race-reason"));
        ResponseEntity<Map<String, Object>> res = post(srcDoctor, "/api/transfers",
                "race-create-" + keySuffix + "-" + UUID.randomUUID(),
                Map.of("patientId", patient.getId().toString(),
                        "sourceAdmissionId", admission.getId().toString(),
                        "destinationHospitalId",
                        hospitals.findByOrganizationIdAndCode(orgId, HOSPITAL_B_CODE).orElseThrow().getId().toString(),
                        "reasonCode", "BED_SHORTAGE"), true);
        assertEquals(201, res.getStatusCode().value(), () -> "seed create failed: " + res.getBody());
        return UUID.fromString(String.valueOf(res.getBody().get("id")));
    }

    private ResponseEntity<Map<String, Object>> accept(String token, UUID transferId, UUID bedId, String key) {
        return post(token, "/api/transfers/" + transferId + "/accept", key,
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", bedId.toString(), "expectedVersion", 0), false);
    }

    private ResponseEntity<Map<String, Object>> post(String token, String path, String key,
                                                     Map<String, Object> body, boolean lenient) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    /** Barrier-synchronized two-client race; every response is classified, no sleeps. */
    private void race(Callable<ResponseEntity<Map<String, Object>>> first,
                      Callable<ResponseEntity<Map<String, Object>>> second,
                      Consumer<HttpStatusCode> classify) throws Exception {
        for (var response : raceResponses(first, second)) {
            classify.accept(response.getStatusCode());
        }
    }

    private List<ResponseEntity<Map<String, Object>>> raceResponses(
            Callable<ResponseEntity<Map<String, Object>>> first,
            Callable<ResponseEntity<Map<String, Object>>> second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<ResponseEntity<Map<String, Object>>> f1 = pool.submit(() -> {
                barrier.await();
                return first.call();
            });
            Future<ResponseEntity<Map<String, Object>>> f2 = pool.submit(() -> {
                barrier.await();
                return second.call();
            });
            return List.of(f1.get(), f2.get());
        }
    }

    private String seedUser(String username, Role role, String hospitalCode, String branchCode) {
        if (accounts.findByUsername(username).isEmpty()) {
            accounts.save(new UserAccount(username,
                    new BCryptPasswordEncoder().encode(TEST_ACCOUNT_PASSWORD), Set.of(role)));
        }
        var account = accounts.findByUsername(username).orElseThrow();
        var org = organizations.findByCode(ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(ORG_CODE, "Xfer Race Network")));
        var hospital = hospitals.findByOrganizationIdAndCode(org.getId(), hospitalCode).orElseGet(() ->
                hospitals.save(new HospitalFacility(org, hospitalCode, "Xfer Race " + hospitalCode,
                        "Race Region", "UTC")));
        var branch = branches.findByHospitalIdAndCode(hospital.getId(), branchCode).orElseGet(() ->
                branches.save(new Branch(hospital, branchCode, "Xfer Race Branch " + branchCode,
                        "Race Way " + branchCode)));
        boolean exists = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).stream()
                .anyMatch(a -> a.getScope() == AssignmentScope.BRANCH
                        && branch.getId().equals(a.getBranch() == null ? null : a.getBranch().getId()));
        if (!exists) {
            assignments.save(ActingAssignment.branch(account, org, role, branch));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> res = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", TEST_ACCOUNT_PASSWORD), headers), MAP);
        assertEquals(200, res.getStatusCode().value(), "login should succeed for " + username);
        return String.valueOf(res.getBody().get("accessToken"));
    }
}

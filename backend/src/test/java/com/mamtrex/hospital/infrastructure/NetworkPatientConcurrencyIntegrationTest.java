package com.mamtrex.hospital.infrastructure;

import com.mamtrex.hospital.TestRuntimeSecrets;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 US3 real-PostgreSQL concurrency evidence (T072). Two genuine HTTP
 * clients race the same network duplicate-MRN patient create simultaneously
 * (barrier-synchronized threads, no sleeps): the global MRN uniqueness plus
 * the service's flush-inside-transaction backstop must yield EXACTLY one
 * legal winner (2xx), one typed 409 loser, and exactly one patient row —
 * across both the same-hospital and the cross-hospital shape. The races
 * repeat to expose timing-sensitive failures.
 *
 * <p>Like {@link PostgresConcurrencyIntegrationTest}, this runs against a
 * real disposable PostgreSQL database owned by the test process; nothing is
 * mocked and no shared or live database is ever touched.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class NetworkPatientConcurrencyIntegrationTest {

    private static final String TEST_JWT_SECRET = TestRuntimeSecrets.jwtSecret();
    private static final String TEST_ACCOUNT_PASSWORD = TestRuntimeSecrets.accountPassword();

    private static final String USER_A = "netrace-user-a";
    private static final String USER_B = "netrace-user-b";
    private static final String ORG_CODE = "NETRACE-ORG";
    private static final String HOSPITAL_A_CODE = "NETRACE-HOSP-A";
    private static final String HOSPITAL_B_CODE = "NETRACE-HOSP-B";
    private static final String BRANCH_A_CODE = "NETRACE-BR-A";
    private static final String BRANCH_B_CODE = "NETRACE-BR-B";

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
    com.mamtrex.hospital.patient.PatientRepository patients;

    private String tokenA;
    private String tokenB;

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
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        for (String username : new String[] {USER_A, USER_B}) {
            if (accounts.findByUsername(username).isEmpty()) {
                accounts.save(new com.mamtrex.hospital.auth.UserAccount(username,
                        encoder.encode(TEST_ACCOUNT_PASSWORD),
                        java.util.Set.of(com.mamtrex.hospital.auth.Role.RECEPTIONIST)));
            }
        }
        var org = organizations.findByCode(ORG_CODE).orElseGet(() ->
                organizations.save(new com.mamtrex.hospital.organization.HospitalOrganization(
                        ORG_CODE, "Network Race Organization")));
        var hospitalA = hospitals.findByOrganizationIdAndCode(org.getId(), HOSPITAL_A_CODE).orElseGet(() ->
                hospitals.save(new com.mamtrex.hospital.organization.HospitalFacility(
                        org, HOSPITAL_A_CODE, "Network Race Hospital A", "Race Region", "UTC")));
        var hospitalB = hospitals.findByOrganizationIdAndCode(org.getId(), HOSPITAL_B_CODE).orElseGet(() ->
                hospitals.save(new com.mamtrex.hospital.organization.HospitalFacility(
                        org, HOSPITAL_B_CODE, "Network Race Hospital B", "Race Region", "UTC")));
        var branchA = branches.findByHospitalIdAndCode(hospitalA.getId(), BRANCH_A_CODE).orElseGet(() ->
                branches.save(new com.mamtrex.hospital.organization.Branch(
                        hospitalA, BRANCH_A_CODE, "Race Branch A", "1 Race Way")));
        var branchB = branches.findByHospitalIdAndCode(hospitalB.getId(), BRANCH_B_CODE).orElseGet(() ->
                branches.save(new com.mamtrex.hospital.organization.Branch(
                        hospitalB, BRANCH_B_CODE, "Race Branch B", "2 Race Way")));
        assignments.save(com.mamtrex.hospital.auth.ActingAssignment.branch(
                accounts.findByUsername(USER_A).orElseThrow(),
                org, com.mamtrex.hospital.auth.Role.RECEPTIONIST, branchA));
        assignments.save(com.mamtrex.hospital.auth.ActingAssignment.branch(
                accounts.findByUsername(USER_B).orElseThrow(),
                org, com.mamtrex.hospital.auth.Role.RECEPTIONIST, branchB));
        this.tokenA = login(USER_A);
        this.tokenB = login(USER_B);
    }

    /** Same-hospital duplicate-MRN race: one winner, one typed 409, one row. */
    @Test
    void concurrentSameHospitalDuplicateMrnCreatesHaveExactlyOneWinner() throws Exception {
        race(tokenA, tokenA, "samehosp");
    }

    /** Cross-hospital duplicate-MRN race: the network uniqueness has one winner. */
    @Test
    void concurrentCrossHospitalDuplicateMrnCreatesHaveExactlyOneWinner() throws Exception {
        race(tokenA, tokenB, "xhosp");
    }

    private void race(String firstToken, String secondToken, String tag) throws Exception {
        for (int round = 1; round <= RACE_ITERATIONS; round++) {
            final String mrn = "MRN-NETRACE-" + tag + "-" + UUID.randomUUID();
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            race(() -> postPatient(firstToken, mrn, "Race First " + mrn),
                 () -> postPatient(secondToken, mrn, "Race Second " + mrn),
                 status -> {
                     if (status.is2xxSuccessful()) winners.incrementAndGet();
                     else if (status.value() == 409) conflicts.incrementAndGet();
                     else throw new AssertionError("unexpected race status: " + status);
                 });
            assertEquals(1, winners.get(), "exactly one create may win the network MRN race");
            assertEquals(1, conflicts.get(), "the loser must receive the typed 409");
            assertEquals(1, patients.findByMedicalRecordNumber(mrn).stream().count(),
                    "exactly one patient row may exist for the raced MRN");
        }
    }

    /** Barrier-synchronized two-client race; every response is classified, no sleeps. */
    private void race(Callable<ResponseEntity<Map<String, Object>>> first,
                      Callable<ResponseEntity<Map<String, Object>>> second,
                      java.util.function.Consumer<org.springframework.http.HttpStatusCode> classify)
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> f1 = pool.submit(() -> {
                barrier.await();
                classify.accept(first.call().getStatusCode());
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                barrier.await();
                classify.accept(second.call().getStatusCode());
                return null;
            });
            f1.get();
            f2.get();
        }
    }

    private ResponseEntity<Map<String, Object>> postPatient(String token, String mrn, String fullName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/patients", HttpMethod.POST,
                new HttpEntity<>(Map.of("medicalRecordNumber", mrn, "fullName", fullName), headers), MAP);
    }

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> res = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", TEST_ACCOUNT_PASSWORD), headers), MAP);
        assertEquals(200, res.getStatusCode().value(), "login should succeed for " + username);
        return String.valueOf(res.getBody().get("accessToken"));
    }
}

package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.TestRuntimeSecrets;
import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.auth.*;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.organization.*;
import com.mamtrex.hospital.transfer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:facility-capacity-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class FacilityCapacityApiTest {
    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", TestRuntimeSecrets::jwtSecret);
        registry.add("HOSPITAL_ADMIN_PASSWORD", TestRuntimeSecrets::accountPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired HospitalOrganizationRepository organizations;
    @Autowired HospitalFacilityRepository hospitals;
    @Autowired BranchRepository branches;
    @Autowired UserAccountRepository accounts;
    @Autowired ActingAssignmentRepository assignments;
    @Autowired PasswordEncoder encoder;
    @Autowired BedRepository beds;
    @Autowired AppointmentRepository appointments;
    @Autowired com.mamtrex.hospital.appointment.AppointmentService appointmentService;
    @Autowired TransferRepository transfers;
    @Autowired TransferBedReservationRepository reservations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private HospitalFacility one, two, foreign;
    private Branch a, b, c, outsider;
    private String networkToken, hospitalToken, branchToken;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalOrganization org = organizations.save(new HospitalOrganization("CAP-" + suffix, "Synthetic network"));
        HospitalOrganization other = organizations.save(new HospitalOrganization("OTHER-" + suffix, "Other network"));
        one = hospitals.save(new HospitalFacility(org, "A-" + suffix, "Hospital A", "Synthetic", "UTC"));
        two = hospitals.save(new HospitalFacility(org, "B-" + suffix, "Hospital B", "Synthetic", "UTC"));
        foreign = hospitals.save(new HospitalFacility(other, "C-" + suffix, "Hospital C", "Synthetic", "UTC"));
        a = branches.save(new Branch(one, "A-" + suffix, "A", "Synthetic"));
        b = branches.save(new Branch(one, "B-" + suffix, "B", "Synthetic"));
        c = branches.save(new Branch(two, "C-" + suffix, "C", "Synthetic"));
        outsider = branches.save(new Branch(foreign, "D-" + suffix, "D", "Synthetic"));
        networkToken = account("net-" + suffix, ActingAssignment.organization(
                accountRow("net-" + suffix), org, Role.ADMIN));
        hospitalToken = account("hosp-" + suffix, ActingAssignment.hospital(
                accountRow("hosp-" + suffix), org, Role.ADMIN, one));
        branchToken = account("branch-" + suffix, ActingAssignment.branch(
                accountRow("branch-" + suffix), org, Role.NURSE, a));
    }

    private UserAccount accountRow(String name) {
        return accounts.save(new UserAccount(name, encoder.encode(TestRuntimeSecrets.accountPassword()), Set.of(Role.ADMIN, Role.NURSE)));
    }
    private String account(String name, ActingAssignment assignment) {
        assignments.save(assignment);
        ResponseEntity<Map<String, Object>> login = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", name, "password", TestRuntimeSecrets.accountPassword()), json()),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, login.getStatusCode());
        return (String) Objects.requireNonNull(login.getBody()).get("accessToken");
    }
    private HttpHeaders json() { HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); return h; }
    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        HttpHeaders h = new HttpHeaders(); if (token != null) h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), new ParameterizedTypeReference<>() {});
    }
    private List<Map<String, Object>> network(String token) {
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(token);
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange("/api/capacity/network", HttpMethod.GET,
                new HttpEntity<>(h), new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return Objects.requireNonNull(response.getBody());
    }
    private long metric(Map<String, Object> map, String key) { return ((Number) map.get(key)).longValue(); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(Map<String, Object> hospital) {
        return (List<Map<String, Object>>) hospital.get("branches");
    }

    @Test
    void inactiveBranchAndHospitalCannotEnterAuthorizedNetworkCounts() {
        beds.save(new Bed(b, "W", "1", "inactive-branch"));
        beds.save(new Bed(c, "W", "1", "inactive-hospital"));
        jdbc.update("update branches set active = false where id = ?", b.getId());
        jdbc.update("update hospitals set active = false where id = ?", two.getId());
        List<Map<String, Object>> rows = network(networkToken);
        assertEquals(1, rows.size());
        assertEquals(one.getId().toString(), rows.getFirst().get("hospitalId"));
        assertEquals(List.of(a.getId().toString()), children(rows.getFirst()).stream()
                .map(x -> x.get("branchId")).toList());
        assertEquals(0, metric(rows.getFirst(), "bedsAvailable"));
    }

    @Test
    void appointmentReadRejectsMismatchedHospitalAncestryEvenWithBranchId() {
        ActingContext inconsistent = new ActingContext("synthetic", UUID.randomUUID(), Role.ADMIN,
                AssignmentScope.BRANCH, one.getOrganization().getId(), two.getId(), a.getId(), null);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(inconsistent,
                        null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            assertThrows(org.springframework.security.access.AccessDeniedException.class, appointmentService::list);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void exactScopedSumsAndReservationLifecycle() {
        Bed free = beds.save(new Bed(a, "W", "1", "free"));
        Bed reserved = beds.save(new Bed(a, "W", "1", "reserved"));
        Bed occupied = beds.save(new Bed(b, "W", "1", "occupied"));
        occupied.markOccupiedByAdmission(); beds.save(occupied);
        beds.save(new Bed(c, "W", "1", "second-hospital"));
        beds.save(new Bed(outsider, "W", "1", "foreign"));
        TransferRequest transfer = transfers.save(new TransferRequest("CAP-" + UUID.randomUUID(), one.getOrganization().getId(),
                UUID.randomUUID(), two.getId(), c.getId(), null, one.getId(), a.getId(), "SYNTHETIC",
                UUID.randomUUID(), Instant.parse("2031-01-01T00:00:00Z")));
        transfer.accept(a.getId(), reserved.getId(), Instant.parse("2031-01-01T00:00:00Z"));
        transfers.save(transfer);
        reservations.save(new TransferBedReservation(transfer.getId(), reserved.getId(), Instant.parse("2031-01-01T00:00:00Z")));
        appointments.save(new Appointment(a, "synthetic", "synthetic", Instant.parse("2031-01-01T09:00:00Z"),
                30, Instant.parse("2031-01-01T09:30:00Z"), "synthetic", "scheduled"));
        appointments.save(new Appointment(outsider, "synthetic", "synthetic", Instant.parse("2031-01-01T09:00:00Z"),
                30, Instant.parse("2031-01-01T09:30:00Z"), "synthetic", "scheduled"));
        Map<String, Object> branch = Objects.requireNonNull(get("/api/capacity/branch", branchToken).getBody());
        assertEquals(Set.of("branchId", "bedsAvailable", "bedsReservedForTransfer", "bedsOccupied", "appointmentsScheduled"), branch.keySet(), branch.toString());
        assertEquals(a.getId().toString(), branch.get("branchId"));
        assertEquals(1, metric(branch, "bedsAvailable"));
        assertEquals(1, metric(branch, "bedsReservedForTransfer"));
        assertEquals(1, metric(branch, "appointmentsScheduled"));
        Map<String, Object> hospital = Objects.requireNonNull(get("/api/capacity/hospital", hospitalToken).getBody());
        assertEquals(one.getId().toString(), hospital.get("hospitalId"));
        assertEquals(Set.of(a.getId().toString(), b.getId().toString()),
                children(hospital).stream().map(x -> x.get("branchId")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, metric(hospital, "bedsOccupied"));
        List<Map<String, Object>> network = network(networkToken);
        assertEquals(Set.of(one.getId().toString(), two.getId().toString()),
                network.stream().map(x -> x.get("hospitalId")).collect(java.util.stream.Collectors.toSet()));
        for (Map<String, Object> h : network) for (String key : List.of("bedsAvailable", "bedsReservedForTransfer", "bedsOccupied", "appointmentsScheduled"))
            assertEquals(metric(h, key), children(h).stream().mapToLong(row -> metric(row, key)).sum());
        assertEquals(2, network.stream().mapToLong(x -> metric(x, "bedsAvailable")).sum());
        TransferBedReservation released = reservations.findByTransferId(transfer.getId()).orElseThrow();
        released.release(Instant.parse("2031-01-02T00:00:00Z"));
        reservations.save(released);
        assertEquals(0, metric(Objects.requireNonNull(get("/api/capacity/branch", branchToken).getBody()), "bedsReservedForTransfer"));
        assertEquals(2, metric(Objects.requireNonNull(get("/api/capacity/branch", branchToken).getBody()), "bedsAvailable"));
    }

    @Test
    void onlyScheduledAndConfirmedAppointmentsCountAsCapacity() {
        Instant first = Instant.parse("2031-03-01T09:00:00Z");
        for (String status : List.of("scheduled", "confirmed", "completed", "cancelled")) {
            appointments.save(new Appointment(a, "synthetic-patient", "synthetic-professional",
                    first, 30, first.plusSeconds(1800), "synthetic", status));
        }
        assertEquals(2, metric(Objects.requireNonNull(get("/api/capacity/branch", branchToken).getBody()),
                "appointmentsScheduled"));
        assertEquals(2, metric(Objects.requireNonNull(get("/api/capacity/hospital", hospitalToken).getBody()),
                "appointmentsScheduled"));
    }

    @Test
    void capacityReadsPinOneDatabaseSnapshotAcrossGroupedQueries() throws Exception {
        for (String method : List.of("branch", "hospital", "network")) {
            var annotation = FacilityCapacityService.class.getMethod(method)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertNotNull(annotation, method + " must own a transaction");
            assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
                    annotation.isolation(), method + " must share one PostgreSQL snapshot across bed and reservation counts");
        }
    }

    @Test
    void capacityNetworkQueryCountIsBoundedAsHospitalsAndBranchesGrow() {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        // Warm the HTTP/security path before measuring its SQL statements.
        assertEquals(2, network(networkToken).size());
        statistics.clear();
        List<Map<String, Object>> small = network(networkToken);
        long smallStatements = statistics.getPrepareStatementCount();
        HospitalOrganization org = one.getOrganization();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        for (int index = 0; index < 3; index++) {
            HospitalFacility extra = hospitals.save(new HospitalFacility(org,
                    "GROW-H-" + index + suffix, "Synthetic growth", "Synthetic", "UTC"));
            for (int branchNo = 0; branchNo < 2; branchNo++) {
                branches.save(new Branch(extra, "GROW-B-" + index + branchNo + suffix,
                        "Synthetic branch", "Synthetic"));
            }
        }
        statistics.clear();
        List<Map<String, Object>> grown = network(networkToken);
        long grownStatements = statistics.getPrepareStatementCount();
        assertEquals(small.size() + 3, grown.size());
        assertEquals(smallStatements, grownStatements,
                "facility capacity must not issue a SQL statement per hospital or branch");
        assertTrue(grownStatements <= 24, "capacity reads must use bounded grouped queries");
    }

    @Test
    void foreignSelectorsCannotExpandAuthorityOrLeakCounts() {
        assertEquals(HttpStatus.FORBIDDEN, get("/api/capacity/network", hospitalToken).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("/api/capacity/hospital", branchToken).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/capacity/branch", null).getStatusCode());
        for (String path : List.of("/api/capacity/hospital?hospitalId=" + foreign.getId(),
                "/api/capacity/network?hospitalId=" + foreign.getId(),
                "/api/capacity/branch?branchId=" + outsider.getId())) {
            assertEquals(HttpStatus.BAD_REQUEST, get(path, networkToken).getStatusCode(), path);
        }
    }
}

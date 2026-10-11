package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.auth.*;
import com.mamtrex.hospital.organization.*;
import com.mamtrex.hospital.patient.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "hospital.jwt.secret=disposable-test-only-network-dashboard-secret-0123456789abcdef",
        "HOSPITAL_ADMIN_PASSWORD=disposable-test-only-network-dashboard-password"
})
class NetworkOperationsDashboardApiTest {
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        var db = com.mamtrex.hospital.infrastructure.PostgresContainerSupport.newIsolatedDatabase();
        org.flywaydb.core.Flyway.configure().locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password()).load().migrate();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired TestRestTemplate rest;
    @Autowired HospitalOrganizationRepository organizations;
    @Autowired HospitalFacilityRepository hospitals;
    @Autowired BranchRepository branches;
    @Autowired UserAccountRepository accounts;
    @Autowired ActingAssignmentRepository assignments;
    @Autowired PatientRepository patients;
    @Autowired com.mamtrex.hospital.transfer.TransferRepository transfers;
    @Autowired com.mamtrex.hospital.transfer.TransferBedReservationRepository reservations;
    @Autowired com.mamtrex.hospital.bed.BedRepository beds;
    @Autowired com.mamtrex.hospital.appointment.AppointmentRepository appointments;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManagerFactory emf;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private static final String PASSWORD = "disposable-test-only-network-dashboard-password";
    private static final List<String> METRICS = List.of("patients", "appointments", "admissions", "emergencyVisits",
            "invoices", "openAdmissions", "activeEmergencyVisits", "bedsAvailable", "bedsOccupied",
            "bedsMaintenance", "bedsOutOfService", "todayAppointments", "invoicesDraft", "invoicesIssued",
            "invoicesPaid", "invoicesVoid", "bedsReservedForTransfer", "appointmentsScheduled", "activeTransfers");

    @Test
    void hospitalAndNetworkFoldOnlyActiveOwnedBranchesAndRetainLegacyKeys() {
        HospitalOrganization org = organizations.save(new HospitalOrganization("NOD-" + suffix, "Synthetic Network " + suffix));
        HospitalFacility a = hospitals.save(new HospitalFacility(org, "A-" + suffix, "Synthetic A", "Synthetic Region", "UTC"));
        HospitalFacility b = hospitals.save(new HospitalFacility(org, "B-" + suffix, "Synthetic B", "Synthetic Region", "UTC"));
        HospitalOrganization foreignOrg = organizations.save(new HospitalOrganization("NOD-OUT-" + suffix, "Synthetic Other Network"));
        HospitalFacility foreignHospital = hospitals.save(new HospitalFacility(foreignOrg, "FOREIGN-" + suffix,
                "Synthetic Other Hospital", "Synthetic Region", "UTC"));
        Branch foreignBranch = branches.save(new Branch(foreignHospital, "OUT-" + suffix, "Synthetic Other Branch", "Synthetic Address"));
        patients.save(new Patient(foreignBranch, "MRN-OUT-" + suffix, "Synthetic Other Patient", null, null, null, null, null, null));
        Branch a1 = branches.save(new Branch(a, "A1-" + suffix, "Synthetic A1", "Synthetic Address"));
        Branch a2 = branches.save(new Branch(a, "A2-" + suffix, "Synthetic A2", "Synthetic Address"));
        Branch b1 = branches.save(new Branch(b, "B1-" + suffix, "Synthetic B1", "Synthetic Address"));
        HospitalFacility inactiveHospital = hospitals.save(new HospitalFacility(org, "Z-" + suffix, "Synthetic Hidden Hospital", "Hidden Region", "UTC"));
        Branch hiddenHospitalBranch = branches.save(new Branch(inactiveHospital, "Z1-" + suffix, "Synthetic Hidden Hospital Branch", "Synthetic Address"));
        Branch inactiveBranch = branches.save(new Branch(a, "A0-" + suffix, "Synthetic Hidden Branch", "Synthetic Address"));
        patients.save(new Patient(inactiveBranch, "MRN-I-" + suffix, "Synthetic Inactive Branch", null, null, null, null, null, null));
        patients.save(new Patient(hiddenHospitalBranch, "MRN-H-" + suffix, "Synthetic Inactive Hospital", null, null, null, null, null, null));
        jdbc.update("update branches set active = false where id = ?", inactiveBranch.getId());
        jdbc.update("update hospitals set active = false where id = ?", inactiveHospital.getId());
        patients.save(new Patient(a1, "MRN-A1-" + suffix, "Synthetic One", null, null, null, null, null, null));
        patients.save(new Patient(a2, "MRN-A2-" + suffix, "Synthetic Two", null, null, null, null, null, null));
        patients.save(new Patient(b1, "MRN-B1-" + suffix, "Synthetic Three", null, null, null, null, null, null));
        // V6 PostgreSQL forbids null organization ownership; inactive ancestry tests the exclusion seam.
        UserAccount admin = accounts.save(new UserAccount("nod-admin-" + suffix, encoder.encode(PASSWORD), Set.of(Role.ADMIN)));
        var adminAssignment = assignments.save(ActingAssignment.organization(admin, org, Role.ADMIN));
        UserAccount hospitalAdmin = accounts.save(new UserAccount("nod-hospital-" + suffix, encoder.encode(PASSWORD), Set.of(Role.ADMIN)));
        assignments.save(ActingAssignment.hospital(hospitalAdmin, org, Role.ADMIN, a));
        var sourcePatient = patients.findByMedicalRecordNumber("MRN-B1-" + suffix).orElseThrow();
        var destinationBed = beds.save(new com.mamtrex.hospital.bed.Bed(a1, "Synthetic Ward", "101", "A1"));
        java.time.Instant moment = java.time.Instant.parse("2037-01-01T00:00:00Z");
        var accepted = new com.mamtrex.hospital.transfer.TransferRequest("TR-A-" + suffix, org.getId(),
                sourcePatient.getId(), b.getId(), b1.getId(), null, a.getId(), null, "SYNTHETIC",
                adminAssignment.getId(), moment);
        accepted.accept(a1.getId(), destinationBed.getId(), moment.plusSeconds(1));
        accepted = transfers.save(accepted);
        reservations.save(new com.mamtrex.hospital.transfer.TransferBedReservation(
                accepted.getId(), destinationBed.getId(), moment.plusSeconds(1)));
        transfers.save(new com.mamtrex.hospital.transfer.TransferRequest("TR-R-" + suffix, org.getId(),
                sourcePatient.getId(), b.getId(), b1.getId(), null, a.getId(), null, "SYNTHETIC",
                adminAssignment.getId(), moment));
        java.time.Instant appointmentTime = java.time.Instant.parse("2037-02-01T09:00:00Z");
        appointments.save(new com.mamtrex.hospital.appointment.Appointment(a1, "synthetic-patient", "synthetic-staff",
                appointmentTime, 30, appointmentTime.plusSeconds(1800), "Consultation", "scheduled"));
        appointments.save(new com.mamtrex.hospital.appointment.Appointment(a1, "synthetic-patient", "synthetic-staff",
                appointmentTime.plusSeconds(3600), 30, appointmentTime.plusSeconds(5400), "Consultation", "cancelled"));
        String networkToken = login(admin.getUsername());
        String hospitalToken = login(hospitalAdmin.getUsername());
        Map<String, Object> network = read("/api/dashboard/network", networkToken);
        assertTrue(network.containsKey("branches"), "legacy network branch list is retained");
        List<Map<String, Object>> hospitalRows = rows(network, "hospitals");
        assertEquals(List.of(a.getId().toString(), b.getId().toString()), hospitalRows.stream().map(h -> h.get("hospitalId")).toList());
        Map<String, Object> own = read("/api/dashboard/hospital", hospitalToken);
        assertEquals(a.getId().toString(), own.get("hospitalId"));
        assertEquals(HttpStatus.FORBIDDEN, get("/api/dashboard/network", hospitalToken).getStatusCode());
        assertEquals(2, rows(own, "branches").size());
        assertFalse(network.toString().contains(inactiveHospital.getId().toString()));
        assertFalse(network.toString().contains(foreignHospital.getId().toString()));
        assertFalse(network.toString().contains(foreignBranch.getId().toString()));
        assertFalse(network.toString().contains(inactiveBranch.getId().toString()));
        assertFalse(own.toString().contains(b.getId().toString()), "hospital read must not disclose other hospital identity");
        for (String metric : METRICS) {
            assertEquals(sum(rows(network, "branches"), metric), value(network, metric), metric + " legacy branch fold");
            assertEquals(sum(hospitalRows, metric), value(network, metric), metric + " hospital fold");
            for (Map<String, Object> hospital : hospitalRows)
                assertEquals(sum(rows(hospital, "branches"), metric), value(hospital, metric), metric + " branch fold");
            assertEquals(value(hospitalRows.get(0), metric), value(own, metric), metric + " acting hospital");
        }
        assertEquals(3L, value(network, "patients"));
        assertEquals(2L, value(own, "patients"));
        assertEquals(1L, value(own, "bedsReservedForTransfer"), "only live destination reservation counts");
        assertEquals(1L, value(own, "activeTransfers"), "ACCEPTED destination counts; REQUESTED does not");
        assertEquals(1L, value(own, "appointmentsScheduled"), "cancelled rows do not count");
        assertEquals(1L, value(own, "bedsAvailable"), "legacy available bed bucket remains raw occupancy count");
        assertEquals(HttpStatus.FORBIDDEN, get("/api/dashboard/hospital", loginBranchOnly(org, b1)).getStatusCode());
    }

    @Test
    void hospitalSummaryContractKeysArriveInServerDeclarationOrder() {
        HospitalOrganization org = organizations.save(new HospitalOrganization("NOD-KEY-" + suffix, "Synthetic Key Order"));
        HospitalFacility a = hospitals.save(new HospitalFacility(org, "KA-" + suffix, "Synthetic Key Hospital", "Synthetic Region", "UTC"));
        branches.save(new Branch(a, "KA1-" + suffix, "Synthetic Key Branch", "Synthetic Address"));
        UserAccount hospitalAdmin = accounts.save(new UserAccount("nod-key-" + suffix, encoder.encode(PASSWORD), Set.of(Role.ADMIN)));
        assignments.save(ActingAssignment.hospital(hospitalAdmin, org, Role.ADMIN, a));
        Map<String, Object> own = read("/api/dashboard/hospital", login(hospitalAdmin.getUsername()));
        var expected = new java.util.ArrayList<>(List.of("hospitalId", "hospitalCode", "hospitalName", "regionLabel"));
        expected.addAll(METRICS);
        expected.add("branches");
        assertEquals(expected, List.copyOf(own.keySet()), "hospital summary JSON key order is contract");
        assertTrue(own.containsKey("hospitalCode"));
    }

    @Test
    void queryCountIsIndependentOfBranchAndHospitalCards() {
        HospitalOrganization org = organizations.save(new HospitalOrganization("NOD-Q-" + suffix, "Synthetic Query Network"));
        HospitalFacility h = hospitals.save(new HospitalFacility(org, "Q-" + suffix, "Synthetic Query Hospital", "Synthetic Region", "UTC"));
        Branch first = branches.save(new Branch(h, "Q0-" + suffix, "Synthetic First", "Synthetic Address"));
        UserAccount admin = accounts.save(new UserAccount("nod-q-" + suffix, encoder.encode(PASSWORD), Set.of(Role.ADMIN)));
        assignments.save(ActingAssignment.organization(admin, org, Role.ADMIN));
        String token = login(admin.getUsername());
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        sf.getStatistics().clear();
        read("/api/dashboard/network", token);
        long small = sf.getStatistics().getPrepareStatementCount();
        for (int i = 1; i <= 8; i++) {
            HospitalFacility next = hospitals.save(new HospitalFacility(org, "QH" + i + "-" + suffix, "Synthetic Query " + i, "Synthetic Region", "UTC"));
            branches.save(new Branch(next, "QB" + i + "-" + suffix, "Synthetic Branch " + i, "Synthetic Address",
                    java.time.ZoneId.of(i % 2 == 0 ? "America/Los_Angeles" : "UTC")));
        }
        sf.getStatistics().clear();
        read("/api/dashboard/network", token);
        long large = sf.getStatistics().getPrepareStatementCount();
        assertEquals(small, large, "branch/hospital card count must not grow SQL statements");
    }

    private String loginBranchOnly(HospitalOrganization org, Branch branch) {
        UserAccount user = accounts.save(new UserAccount("nod-branch-" + suffix, encoder.encode(PASSWORD), Set.of(Role.ADMIN)));
        assignments.save(ActingAssignment.branch(user, org, Role.ADMIN, branch));
        return login(user.getUsername());
    }
    private String login(String username) {
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD)), new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode(), "synthetic login");
        return (String) Objects.requireNonNull(response.getBody()).get("accessToken");
    }
    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
    }
    private Map<String, Object> read(String path, String token) {
        ResponseEntity<Map<String, Object>> response = get(path, token);
        assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        return Objects.requireNonNull(response.getBody());
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.get(key);
    }
    private static long value(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static long sum(List<Map<String, Object>> rows, String key) { return rows.stream().mapToLong(r -> value(r, key)).sum(); }
}

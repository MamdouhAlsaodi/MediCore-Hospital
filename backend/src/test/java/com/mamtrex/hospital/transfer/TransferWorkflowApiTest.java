package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.Patient;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 US4 workflow API contract (specs/005 tasks T090–T098, FR-014–
 * FR-019, FR-026): the full transfer lifecycle over real HTTP against an
 * isolated in-memory H2 database — server-derived source ownership, scoped
 * visibility (foreign hospitals get a generic 404), mandatory idempotency
 * keys on state changes with replay and payload-conflict semantics, the
 * atomic bed reservation, the destination access grant on accept, the
 * source admission/bed handoff on transit start, the one-transaction
 * completion (destination admission + reservation consumed + bed occupied),
 * and exactly one transfer-context audit row per successful transition.
 * Synthetic data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:transfer-api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TransferWorkflowApiTest {

    private static final String TEST_JWT_SECRET = com.mamtrex.hospital.TestRuntimeSecrets.jwtSecret();
    private static final String TEST_ACCOUNT_PASSWORD = com.mamtrex.hospital.TestRuntimeSecrets.accountPassword();

    @org.springframework.test.context.DynamicPropertySource
    static void testCredentials(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("HOSPITAL_ADMIN_PASSWORD", () -> TEST_ACCOUNT_PASSWORD);
    }

    private static final String ORG_CODE = "XFER-ORG";
    private static final String HOSPITAL_A_CODE = "XFER-HOSP-A";
    private static final String HOSPITAL_B_CODE = "XFER-HOSP-B";
    private static final String HOSPITAL_C_CODE = "XFER-HOSP-C";

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
    @Autowired com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository bedAssignments;
    @Autowired com.mamtrex.hospital.audit.AuditEventRepository auditEvents;

    private String srcDoctor;
    private String srcNurse;
    private String dstDoctor;
    private String dstNurse;
    private String foreignDoctor;

    private UUID patientId;
    private UUID admissionId;
    private UUID sourceBedId;
    private UUID destBedId;
    private UUID destBranchId;
    private UUID destHospitalId;
    private UUID foreignHospitalId;

    @BeforeEach
    void seed() {
        var encoder = new BCryptPasswordEncoder();
        srcDoctor = seedUser("xfer-src-doctor", Role.DOCTOR, HOSPITAL_A_CODE, "A");
        srcNurse = seedUser("xfer-src-nurse", Role.NURSE, HOSPITAL_A_CODE, "A");
        dstDoctor = seedUser("xfer-dst-doctor", Role.DOCTOR, HOSPITAL_B_CODE, "B");
        dstNurse = seedUser("xfer-dst-nurse", Role.NURSE, HOSPITAL_B_CODE, "B");
        foreignDoctor = seedUser("xfer-foreign-doctor", Role.DOCTOR, HOSPITAL_C_CODE, "C");

        var org = organizations.findByCode(ORG_CODE).orElseThrow();
        var hospitalA = hospitals.findByOrganizationIdAndCode(org.getId(), HOSPITAL_A_CODE).orElseThrow();
        var branchA = branches.findByHospitalIdAndCode(hospitalA.getId(), "A").orElseThrow();
        var hospitalB = hospitals.findByOrganizationIdAndCode(org.getId(), HOSPITAL_B_CODE).orElseThrow();
        destHospitalId = hospitalB.getId();
        destBranchId = branches.findByHospitalIdAndCode(hospitalB.getId(), "B").orElseThrow().getId();
        foreignHospitalId = hospitals.findByOrganizationIdAndCode(org.getId(), HOSPITAL_C_CODE).orElseThrow().getId();

        // Idempotent per-test fixtures (the shared context re-runs @BeforeEach).
        if (patients.findByMedicalRecordNumber("MRN-XFER-1").isEmpty()) {
            patientId = patients.save(new Patient(branchA, "MRN-XFER-1", "Xfer Patient",
                    java.time.LocalDate.of(1990, 1, 1), null, null, null, null, null)).getId();
        } else {
            patientId = patients.findByMedicalRecordNumber("MRN-XFER-1").orElseThrow().getId();
        }
        // The H2 test schema has no V6 backfill: give the acting hospital its
        // LOCAL_REGISTRATION visibility grant idempotently.
        if (!accessGrants.existsByPatientIdAndHospitalIdAndStatus(patientId, hospitalA.getId(),
                com.mamtrex.hospital.patient.PatientAccessStatus.ACTIVE)) {
            accessGrants.save(new com.mamtrex.hospital.patient.PatientHospitalAccess(
                    patients.findById(patientId).orElseThrow(), hospitalA,
                    com.mamtrex.hospital.patient.PatientAccessSource.LOCAL_REGISTRATION, null));
        }
        if (admissions.findByBranchId(branchA.getId()).stream()
                .noneMatch(a -> a.getPatientId().equals(patientId.toString()))) {
            admissionId = admissions.save(new com.mamtrex.hospital.admission.Admission(
                    branchA.getId(), patientId.toString(), Instant.now(), "xfer-source-reason")).getId();
        } else {
            admissionId = admissions.findByBranchId(branchA.getId()).stream()
                    .filter(a -> a.getPatientId().equals(patientId.toString())).findFirst().orElseThrow().getId();
        }
        sourceBedId = seedBed(branchA, "SA", "1", "SRC-" + UUID.randomUUID());
        destBedId = seedBed(branches.findById(destBranchId).orElseThrow(), "WB", "2", "DST-" + UUID.randomUUID());
    }

    private UUID seedBed(Branch branch, String ward, String room, String number) {
        var existing = beds.findByBranchIdAndWardAndRoomAndBedNumber(branch.getId(), ward, room, number);
        if (existing.isPresent()) {
            return existing.orElseThrow().getId();
        }
        return beds.save(new com.mamtrex.hospital.bed.Bed(branch, ward, room, number)).getId();
    }

    private String seedUser(String username, Role role, String hospitalCode, String branchCode) {
        if (accounts.findByUsername(username).isEmpty()) {
            accounts.save(new UserAccount(username, new BCryptPasswordEncoder().encode(TEST_ACCOUNT_PASSWORD),
                    Set.of(role)));
        }
        var account = accounts.findByUsername(username).orElseThrow();
        var org = organizations.findByCode(ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(ORG_CODE, "Xfer Network")));
        var hospital = hospitals.findByOrganizationIdAndCode(org.getId(), hospitalCode).orElseGet(() ->
                hospitals.save(new HospitalFacility(org, hospitalCode, "Xfer " + hospitalCode, "Region", "UTC")));
        var branch = branches.findByHospitalIdAndCode(hospital.getId(), branchCode).orElseGet(() ->
                branches.save(new Branch(hospital, branchCode, "Xfer Branch " + branchCode, "Way " + branchCode)));
        boolean exists = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId()).stream()
                .anyMatch(a -> a.getScope() == com.mamtrex.hospital.auth.AssignmentScope.BRANCH
                        && branch.getId().equals(a.getBranch() == null ? null : a.getBranch().getId()));
        if (!exists) {
            assignments.save(ActingAssignment.branch(account, org, role, branch));
        }
        return login(username);
    }

    @Test
    void sourceDoctorCreatesRequestWithServerDerivedSource() {
        Map<String, Object> body = createTransfer(srcDoctor, "key-create-1");
        assertEquals("REQUESTED", body.get("status"));
        assertEquals(patientId.toString(), String.valueOf(body.get("patientId")));
        assertEquals("BED_SHORTAGE", body.get("reasonCode"));
        assertEquals(0, ((Number) body.get("version")).intValue());
        assertNull(body.get("destinationBedId"));
        assertNotNull(body.get("id"));
        assertNotNull(body.get("transferNumber"));
        // exactly one success audit row with transfer context
        List<com.mamtrex.hospital.audit.AuditEvent> events = auditEvents.findAll().stream()
                .filter(e -> "TRANSFER_REQUEST".equals(e.getAction())
                        && e.getTransferId() != null
                        && UUID.fromString(String.valueOf(body.get("id"))).equals(e.getTransferId()))
                .toList();
        assertEquals(1, events.size());
    }

    @Test
    void stateChangeWithoutIdempotencyKeyIsRejected() {
        HttpHeaders headers = jsonHeaders(srcDoctor);
        ResponseEntity<Map<String, Object>> res = rest.exchange("/api/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("patientId", patientId.toString(),
                        "sourceAdmissionId", admissionId.toString(),
                        "destinationHospitalId", destHospitalId.toString(),
                        "reasonCode", "BED_SHORTAGE"), headers), MAP);
        assertEquals(400, res.getStatusCode().value());
    }

    @Test
    void destinationSeesTheRequestForeignHospitalDoesNot() {
        String id = createAndGetId(srcDoctor, "key-visibility-1");
        assertEquals(200, get(srcDoctor, id).getStatusCode().value());
        assertEquals(200, get(dstDoctor, id).getStatusCode().value());
        assertEquals(404, get(foreignDoctor, id).getStatusCode().value());

        List<Map<String, Object>> dstList = list(dstDoctor);
        assertTrue(dstList.stream().anyMatch(t -> id.equals(String.valueOf(t.get("id")))));
        List<Map<String, Object>> foreignList = list(foreignDoctor);
        assertTrue(foreignList.stream().noneMatch(t -> id.equals(String.valueOf(t.get("id")))));
    }

    @Test
    void destinationDoctorAcceptsAndReservesBedAtomically() {
        String id = createAndGetId(srcDoctor, "key-accept-1");
        Map<String, Object> body = post(dstDoctor, "/api/transfers/" + id + "/accept", "key-accept-1",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        assertEquals("ACCEPTED", body.get("status"));
        assertEquals(destBedId.toString(), String.valueOf(body.get("destinationBedId")));
        assertEquals(1, ((Number) body.get("version")).intValue());

        var reservation = reservations.findByTransferId(UUID.fromString(id)).orElseThrow();
        assertEquals(ReservationStatus.ACTIVE, reservation.getStatus());
        assertEquals(destBedId, reservation.getBedId());
        // destination patient access grant exists
        assertTrue(accessGrants.existsByPatientIdAndHospitalIdAndStatus(patientId, destHospitalId,
                com.mamtrex.hospital.patient.PatientAccessStatus.ACTIVE));
    }

    @Test
    void sameIdempotencyKeyReplaysTheOriginalOutcome() {
        String id = createAndGetId(srcDoctor, "key-replay-1");
        Map<String, Object> first = post(dstDoctor, "/api/transfers/" + id + "/accept", "key-replay-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        long versionAfterFirst = ((Number) first.get("version")).longValue();
        Map<String, Object> second = post(dstDoctor, "/api/transfers/" + id + "/accept", "key-replay-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        assertEquals(first.get("id"), second.get("id"));
        assertEquals(versionAfterFirst, ((Number) second.get("version")).longValue());
        assertEquals(1, reservations.findByTransferId(UUID.fromString(id)).stream().count());
    }

    @Test
    void sameKeyWithDifferentPayloadIsAConflict() {
        String id = createAndGetId(srcDoctor, "key-conflict-1");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-conflict-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        ResponseEntity<Map<String, Object>> conflict = raw(dstDoctor,
                "/api/transfers/" + id + "/accept", "key-conflict-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        assertEquals(200, conflict.getStatusCode().value());
        // different payload, same key -> 409
        var otherBed = beds.findByBranchId(destBranchId).stream()
                .filter(b -> !b.getId().equals(destBedId)
                        && com.mamtrex.hospital.bed.Bed.STATUS_AVAILABLE.equals(b.getOccupancyStatus()))
                .findFirst();
        if (otherBed.isPresent()) {
            ResponseEntity<Map<String, Object>> res = raw(dstDoctor, "/api/transfers/" + id + "/accept",
                    "key-conflict-2",
                    Map.of("destinationBranchId", destBranchId.toString(),
                            "destinationBedId", otherBed.orElseThrow().getId().toString(),
                            "expectedVersion", 1));
            assertEquals(409, res.getStatusCode().value());
        }
    }

    @Test
    void destinationNurseCannotAcceptButSourceCannotEither() {
        String id = createAndGetId(srcDoctor, "key-roles-1");
        assertEquals(403, raw(srcNurse, "/api/transfers/" + id + "/accept", "key-roles-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0)).getStatusCode().value());
        assertEquals(403, raw(dstNurse, "/api/transfers/" + id + "/accept", "key-roles-3",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0)).getStatusCode().value());
    }

    @Test
    void rejectIsTerminalAndSourceScopedOnly() {
        String id = createAndGetId(srcDoctor, "key-reject-1");
        assertEquals(403, raw(srcDoctor, "/api/transfers/" + id + "/reject", "key-reject-2",
                Map.of("reasonCode", "BED_SHORTAGE", "expectedVersion", 0)).getStatusCode().value());
        Map<String, Object> body = post(dstDoctor, "/api/transfers/" + id + "/reject", "key-reject-3",
                Map.of("reasonCode", "BED_SHORTAGE", "expectedVersion", 0));
        assertEquals("REJECTED", body.get("status"));
        assertEquals(409, raw(dstDoctor, "/api/transfers/" + id + "/reject", "key-reject-4",
                Map.of("reasonCode", "BED_SHORTAGE", "expectedVersion", 1)).getStatusCode().value());
    }

    @Test
    void cancelBeforeAcceptLeavesNoReservation() {
        String id = createAndGetId(srcDoctor, "key-cancel-1");
        Map<String, Object> body = post(srcNurse, "/api/transfers/" + id + "/cancel", "key-cancel-2",
                Map.of("reasonCode", "PATIENT_PREFERENCE", "expectedVersion", 0));
        assertEquals("CANCELLED", body.get("status"));
        assertTrue(reservations.findByTransferId(UUID.fromString(id)).isEmpty());
        // a destination actor may not cancel (FR-016); an unknown id stays a generic 404
        assertEquals(403, raw(dstDoctor, "/api/transfers/" + id + "/cancel", "key-cancel-3",
                Map.of("reasonCode", "BED_SHORTAGE", "expectedVersion", 0)).getStatusCode().value());
        assertEquals(404, raw(dstDoctor, "/api/transfers/" + UUID.randomUUID() + "/cancel", "key-cancel-4",
                Map.of("reasonCode", "BED_SHORTAGE", "expectedVersion", 0)).getStatusCode().value());
    }

    @Test
    void acceptedTransferCancelReleasesTheReservation() {
        String id = createAndGetId(srcDoctor, "key-cancelacc-1");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-cancelacc-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        Map<String, Object> body = post(srcDoctor, "/api/transfers/" + id + "/cancel", "key-cancelacc-3",
                Map.of("reasonCode", "PATIENT_PREFERENCE", "expectedVersion", 1));
        assertEquals("CANCELLED", body.get("status"));
        assertEquals(ReservationStatus.RELEASED,
                reservations.findByTransferId(UUID.fromString(id)).orElseThrow().getStatus());
        var bed = beds.findById(destBedId).orElseThrow();
        assertEquals(com.mamtrex.hospital.bed.Bed.STATUS_AVAILABLE, bed.getOccupancyStatus());
    }

    @Test
    void startTransitIsSourceOnlyAndHandsOffSourceAdmissionAndBed() {
        String id = createAndGetId(srcDoctor, "key-transit-1");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-transit-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        // destination actor may not start transit (FR-016)
        assertEquals(403, raw(dstDoctor, "/api/transfers/" + id + "/start-transit", "key-transit-3",
                Map.of()).getStatusCode().value());

        // give the transfer's own source admission a bed so the handoff is observable
        var transferAdmissionId = lastCreatedAdmissionId;
        var sourceBed = beds.findById(sourceBedId).orElseThrow();
        sourceBed.markOccupiedByAdmission();
        beds.save(sourceBed);
        bedAssignments.save(new com.mamtrex.hospital.admission.AdmissionBedAssignment(transferAdmissionId, sourceBedId));

        Map<String, Object> body = post(srcDoctor, "/api/transfers/" + id + "/start-transit", "key-transit-4",
                Map.of());
        assertEquals("IN_TRANSIT", body.get("status"));
        // source admission discharged and its bed released
        assertEquals(com.mamtrex.hospital.admission.Admission.STATUS_DISCHARGED,
                admissions.findById(transferAdmissionId).orElseThrow().getStatus());
        assertTrue(bedAssignments.findByAdmissionId(transferAdmissionId).isEmpty());
        assertEquals(com.mamtrex.hospital.bed.Bed.STATUS_AVAILABLE,
                beds.findById(sourceBedId).orElseThrow().getOccupancyStatus());
    }

    @Test
    void completionCreatesDestinationAdmissionConsumesReservationOccupiesBed() {
        String id = createAndGetId(srcDoctor, "key-complete-1");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-complete-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        post(srcDoctor, "/api/transfers/" + id + "/start-transit", "key-complete-3", Map.of());

        assertEquals(403, raw(srcDoctor, "/api/transfers/" + id + "/complete", "key-complete-4",
                Map.of()).getStatusCode().value());
        Map<String, Object> body = post(dstDoctor, "/api/transfers/" + id + "/complete", "key-complete-5",
                Map.of());
        assertEquals("COMPLETED", body.get("status"));

        // one-transaction completion effects
        assertEquals(ReservationStatus.CONSUMED,
                reservations.findByTransferId(UUID.fromString(id)).orElseThrow().getStatus());
        assertEquals(com.mamtrex.hospital.bed.Bed.STATUS_OCCUPIED,
                beds.findById(destBedId).orElseThrow().getOccupancyStatus());
        var destAdmission = admissions.findByBranchId(destBranchId).stream()
                .filter(a -> a.getPatientId().equals(patientId.toString())).findFirst().orElseThrow();
        assertEquals(com.mamtrex.hospital.admission.Admission.STATUS_ADMITTED, destAdmission.getStatus());
        assertEquals(destBedId, bedAssignments.findByAdmissionId(destAdmission.getId()).orElseThrow().getBedId());
        // repeated completion is refused
        assertEquals(409, raw(dstDoctor, "/api/transfers/" + id + "/complete", "key-complete-6",
                Map.of()).getStatusCode().value());
    }

    @Test
    void staleExpectedVersionIsAConflict() {
        String id = createAndGetId(srcDoctor, "key-stale-1");
        ResponseEntity<Map<String, Object>> res = raw(dstDoctor, "/api/transfers/" + id + "/accept",
                "key-stale-2",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 7));
        assertEquals(409, res.getStatusCode().value());
        assertEquals("REQUESTED", get(srcDoctor, id).getBody().get("status"));
    }

    @Test
    void unknownReasonCodeIsRejected() {
        HttpHeaders headers = jsonHeaders(srcDoctor);
        headers.set("Idempotency-Key", "key-reason-1");
        ResponseEntity<Map<String, Object>> res = rest.exchange("/api/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("patientId", patientId.toString(),
                        "sourceAdmissionId", admissionId.toString(),
                        "destinationHospitalId", destHospitalId.toString(),
                        "reasonCode", "MAKE_IT_SO"), headers), MAP);
        assertEquals(400, res.getStatusCode().value());
    }

    @Test
    void reservedBedCannotBeChangedOrAssignedThroughExistingWorkflows() {
        String id = createAndGetId(srcDoctor, "key-reserved-workflows-create");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-reserved-workflows-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        HttpHeaders headers = jsonHeaders(dstDoctor);
        var status = rest.exchange("/api/beds/" + destBedId + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "MAINTENANCE"), headers), MAP);
        assertEquals(409, status.getStatusCode().value());
        var deletion = rest.exchange("/api/beds/" + destBedId, HttpMethod.DELETE,
                new HttpEntity<>(headers), MAP);
        assertEquals(409, deletion.getStatusCode().value());
        var another = admissions.save(new com.mamtrex.hospital.admission.Admission(
                destBranchId, patientId.toString(), Instant.now(), "synthetic"));
        var assign = rest.exchange("/api/admissions/" + another.getId() + "/bed", HttpMethod.PUT,
                new HttpEntity<>(Map.of("bedId", destBedId.toString()), headers), MAP);
        assertEquals(409, assign.getStatusCode().value());
        assertTrue(bedAssignments.findByAdmissionId(another.getId()).isEmpty());
        var create = rest.exchange("/api/admissions", HttpMethod.POST,
                new HttpEntity<>(Map.of("patientId", patientId.toString(),
                        "admittedAt", "2026-01-01T12:00:00", "reason", "synthetic",
                        "bedId", destBedId.toString()), headers), MAP);
        assertEquals(409, create.getStatusCode().value());
    }

    @Test
    void transitRejectsAnAlreadyDischargedSourceAdmission() {
        String id = createAndGetId(srcDoctor, "key-discharged-create");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-discharged-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        var admission = admissions.findById(lastCreatedAdmissionId).orElseThrow();
        admission.dischargeAt(Instant.now());
        admissions.save(admission);
        assertEquals(409, raw(srcDoctor, "/api/transfers/" + id + "/start-transit",
                "key-discharged-transit", Map.of()).getStatusCode().value());
        assertEquals("ACCEPTED", get(srcDoctor, id).getBody().get("status"));
    }

    @Test
    void transitRejectsAssignmentWhenItsBedIsNotAdmissionOccupied() {
        String id = createAndGetId(srcDoctor, "key-source-owner-create");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-source-owner-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        bedAssignments.save(new com.mamtrex.hospital.admission.AdmissionBedAssignment(lastCreatedAdmissionId, sourceBedId));
        assertEquals(409, raw(srcDoctor, "/api/transfers/" + id + "/start-transit",
                "key-source-owner-transit", Map.of()).getStatusCode().value());
        assertEquals(com.mamtrex.hospital.admission.Admission.STATUS_ADMITTED,
                admissions.findById(lastCreatedAdmissionId).orElseThrow().getStatus());
    }

    @Test
    void completionRejectsBedThatStoppedBeingAvailable() {
        String id = createAndGetId(srcDoctor, "key-completion-check-create");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-completion-check-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        post(srcDoctor, "/api/transfers/" + id + "/start-transit", "key-completion-check-transit", Map.of());
        var bed = beds.findById(destBedId).orElseThrow();
        bed.changeOperationalStatus(com.mamtrex.hospital.bed.Bed.STATUS_MAINTENANCE);
        beds.save(bed);
        assertEquals(409, raw(dstDoctor, "/api/transfers/" + id + "/complete",
                "key-completion-check-complete", Map.of()).getStatusCode().value());
        assertEquals(ReservationStatus.ACTIVE, reservations.findByTransferId(UUID.fromString(id)).orElseThrow().getStatus());
    }

    @Test
    void createReplayRetainsOriginalViewAfterLaterTransition() {
        UUID source = freshAdmissionId();
        Map<String, Object> body = Map.of("patientId", patientId.toString(),
                "sourceAdmissionId", source.toString(), "destinationHospitalId", destHospitalId.toString(),
                "reasonCode", "BED_SHORTAGE");
        var original = raw(srcDoctor, "/api/transfers", "key-snapshot-create", body);
        assertEquals(201, original.getStatusCode().value());
        String id = String.valueOf(original.getBody().get("id"));
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-snapshot-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        var replay = raw(srcDoctor, "/api/transfers", "key-snapshot-create", body);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(original.getBody(), replay.getBody());
    }

    @Test
    void simultaneousSameKeyCreatesShareOneOriginalOutcome() throws Exception {
        UUID source = freshAdmissionId();
        Map<String, Object> body = Map.of("patientId", patientId.toString(),
                "sourceAdmissionId", source.toString(), "destinationHospitalId", destHospitalId.toString(),
                "reasonCode", "BED_SHORTAGE");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<ResponseEntity<Map<String, Object>>> request = () -> {
                ready.countDown();
                start.await();
                return raw(srcDoctor, "/api/transfers", "key-simultaneous-create", body);
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(201, a.getStatusCode().value(), () -> String.valueOf(a.getBody()));
            assertEquals(201, b.getStatusCode().value(), () -> String.valueOf(b.getBody()));
            assertEquals(a.getBody(), b.getBody());
            assertEquals(1, transfers.findAll().stream()
                    .filter(t -> t.getSourceAdmissionId().equals(source)).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void listScopesHospitalAndStatusBeforeTheTwoHundredRowLimit() {
        var org = organizations.findByCode(ORG_CODE).orElseThrow();
        var hospitalA = hospitals.findByOrganizationIdAndCode(org.getId(), HOSPITAL_A_CODE).orElseThrow();
        var branchA = branches.findByHospitalIdAndCode(hospitalA.getId(), "A").orElseThrow();
        var hospitalC = hospitals.findById(foreignHospitalId).orElseThrow();
        var branchC = branches.findByHospitalIdAndCode(hospitalC.getId(), "C").orElseThrow();
        var actor = assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(
                accounts.findByUsername("xfer-src-doctor").orElseThrow().getId()).get(0);
        var visible = transfers.save(new TransferRequest("TR-" + UUID.randomUUID(), org.getId(), patientId,
                hospitalA.getId(), branchA.getId(), admissionId, destHospitalId, null,
                "BED_SHORTAGE", actor.getId(), Instant.now().minusSeconds(3600)));
        var foreign = new java.util.ArrayList<TransferRequest>();
        for (int i = 0; i < 205; i++) {
            foreign.add(new TransferRequest("TR-" + UUID.randomUUID(), org.getId(), patientId,
                    hospitalC.getId(), branchC.getId(), admissionId, destHospitalId, null,
                    "BED_SHORTAGE", actor.getId(), Instant.now().minusSeconds(i)));
        }
        transfers.saveAll(foreign);
        assertTrue(list(srcDoctor).stream().anyMatch(t -> visible.getId().toString().equals(t.get("id"))));
        visible.accept(destBranchId, destBedId, Instant.now());
        transfers.save(visible);
        var requested = new java.util.ArrayList<TransferRequest>();
        for (int i = 0; i < 205; i++) {
            requested.add(new TransferRequest("TR-" + UUID.randomUUID(), org.getId(), patientId,
                    hospitalA.getId(), branchA.getId(), admissionId, destHospitalId, null,
                    "BED_SHORTAGE", actor.getId(), Instant.now().minusSeconds(i)));
        }
        transfers.saveAll(requested);
        var filtered = rest.exchange("/api/transfers?status=ACCEPTED", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(srcDoctor)), new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertEquals(200, filtered.getStatusCode().value());
        assertTrue(filtered.getBody().stream().anyMatch(t -> visible.getId().toString().equals(t.get("id"))));
    }

    @Test
    void destinationDischargeReleasesTransferBedThroughAdmissionWorkflow() {
        String id = createAndGetId(srcDoctor, "key-dest-release-create");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-dest-release-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        post(srcDoctor, "/api/transfers/" + id + "/start-transit", "key-dest-release-transit", Map.of());
        post(dstDoctor, "/api/transfers/" + id + "/complete", "key-dest-release-complete", Map.of());
        UUID destinationAdmissionId = admissions.findByBranchId(destBranchId).stream()
                .filter(a -> bedAssignments.findByAdmissionId(a.getId())
                        .map(assignment -> destBedId.equals(assignment.getBedId())).orElse(false))
                .findFirst().orElseThrow().getId();
        var discharged = rest.exchange("/api/admissions/" + destinationAdmissionId + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "DISCHARGED"), jsonHeaders(dstDoctor)), MAP);
        assertEquals(200, discharged.getStatusCode().value(), () -> String.valueOf(discharged.getBody()));
        assertTrue(bedAssignments.findByAdmissionId(destinationAdmissionId).isEmpty());
        assertEquals(com.mamtrex.hospital.bed.Bed.STATUS_AVAILABLE,
                beds.findById(destBedId).orElseThrow().getOccupancyStatus());
    }

    @Test
    void acceptReplayRetainsOriginalViewAfterTransit() {
        String id = createAndGetId(srcDoctor, "key-accept-snapshot-create");
        Map<String, Object> request = Map.of("destinationBranchId", destBranchId.toString(),
                "destinationBedId", destBedId.toString(), "expectedVersion", 0);
        var original = raw(dstDoctor, "/api/transfers/" + id + "/accept", "key-accept-snapshot", request);
        assertEquals(200, original.getStatusCode().value());
        post(srcDoctor, "/api/transfers/" + id + "/start-transit", "key-accept-snapshot-transit", Map.of());
        var replay = raw(dstDoctor, "/api/transfers/" + id + "/accept", "key-accept-snapshot", request);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(original.getBody(), replay.getBody());
    }

    @Test
    void completionRejectsACompetingAdmissionAssignmentDespiteAvailableBedStatus() {
        String id = createAndGetId(srcDoctor, "key-completion-assignment-create");
        post(dstDoctor, "/api/transfers/" + id + "/accept", "key-completion-assignment-accept",
                Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0));
        post(srcDoctor, "/api/transfers/" + id + "/start-transit", "key-completion-assignment-transit", Map.of());
        var admission = admissions.save(new com.mamtrex.hospital.admission.Admission(
                destBranchId, patientId.toString(), Instant.now(), "synthetic"));
        bedAssignments.save(new com.mamtrex.hospital.admission.AdmissionBedAssignment(admission.getId(), destBedId));
        assertEquals(409, raw(dstDoctor, "/api/transfers/" + id + "/complete",
                "key-completion-assignment-complete", Map.of()).getStatusCode().value());
        assertEquals(ReservationStatus.ACTIVE, reservations.findByTransferId(UUID.fromString(id)).orElseThrow().getStatus());
    }

    @Test
    void acceptRejectsAnAdmissionAssignmentEvenWhenBedStatusSaysAvailable() {
        String id = createAndGetId(srcDoctor, "key-accept-assignment-create");
        var admission = admissions.save(new com.mamtrex.hospital.admission.Admission(
                destBranchId, patientId.toString(), Instant.now(), "synthetic"));
        bedAssignments.save(new com.mamtrex.hospital.admission.AdmissionBedAssignment(admission.getId(), destBedId));
        assertEquals(409, raw(dstDoctor, "/api/transfers/" + id + "/accept",
                "key-accept-assignment-accept", Map.of("destinationBranchId", destBranchId.toString(),
                        "destinationBedId", destBedId.toString(), "expectedVersion", 0)).getStatusCode().value());
        assertTrue(reservations.findByTransferId(UUID.fromString(id)).isEmpty());
    }

    // ------------------------------------------------------------- helpers

    /** A fresh ADMITTED admission per create: discharged admissions from earlier lifecycle tests must not poison later ones. */
    private UUID lastCreatedAdmissionId;

    private UUID freshAdmissionId() {
        var branchA = branches.findByHospitalIdAndCode(
                hospitals.findByOrganizationIdAndCode(organizations.findByCode(ORG_CODE).orElseThrow().getId(),
                        HOSPITAL_A_CODE).orElseThrow().getId(), "A").orElseThrow();
        lastCreatedAdmissionId = admissions.save(new com.mamtrex.hospital.admission.Admission(
                branchA.getId(), patientId.toString(), Instant.now(), "xfer-source-reason")).getId();
        return lastCreatedAdmissionId;
    }

    private Map<String, Object> createTransfer(String token, String key) {
        UUID freshAdmission = freshAdmissionId();
        ResponseEntity<Map<String, Object>> res = raw(token, "/api/transfers", key,
                Map.of("patientId", patientId.toString(),
                        "sourceAdmissionId", freshAdmission.toString(),
                        "destinationHospitalId", destHospitalId.toString(),
                        "reasonCode", "BED_SHORTAGE"));
        assertEquals(201, res.getStatusCode().value(), () -> "create -> " + res.getBody());
        return res.getBody();
    }

    private String createAndGetId(String token, String key) {
        return String.valueOf(createTransfer(token, key).get("id"));
    }

    private Map<String, Object> post(String token, String path, String key, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> res = raw(token, path, key, body);
        assertEquals(200, res.getStatusCode().value(), () -> "POST " + path + " -> " + res.getBody());
        return res.getBody();
    }

    private ResponseEntity<Map<String, Object>> raw(String token, String path, String key,
                                                    Map<String, Object> body) {
        HttpHeaders headers = jsonHeaders(token);
        headers.set("Idempotency-Key", key);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String token, String id) {
        HttpHeaders headers = jsonHeaders(token);
        return rest.exchange("/api/transfers/" + id, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    private List<Map<String, Object>> list(String token) {
        HttpHeaders headers = jsonHeaders(token);
        ResponseEntity<List<Map<String, Object>>> res = rest.exchange("/api/transfers", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertEquals(200, res.getStatusCode().value());
        return res.getBody();
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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

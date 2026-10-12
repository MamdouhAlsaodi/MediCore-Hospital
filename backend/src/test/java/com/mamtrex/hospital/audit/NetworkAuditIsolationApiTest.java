package com.mamtrex.hospital.audit;

import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
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
import com.mamtrex.hospital.patient.PatientRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 US7 T127/T128 (FR-026/FR-027/FR-028): the hospital-aware audit
 * read matrix over real HTTP against a disposable real PostgreSQL database
 * (Flyway-migrated, throwaway, owned by the test process — synthetic records
 * only).
 *
 * <p>Fixture: one synthetic network with three hospitals, two branches on
 * hospital A and hospital C, one branch on hospital B, plus a foreign-network
 * hospital, and acting accounts for the ORGANIZATION, HOSPITAL (hospital A),
 * and BRANCH (C1) scopes. Audit rows are seeded directly through the
 * repository with controlled acting contexts so every scope slice is
 * deterministic: rows attributed to each branch/hospital, two transfer
 * rows with bounded source A → destination B context (the request recorded
 * under an A actor, the accept under a B actor), one context-less legacy
 * row, and one foreign-network row.</p>
 *
 * <p>T127 proves the read slices: a HOSPITAL-scoped ADMIN inspects the
 * hospital-wide evidence (every branch of the acting hospital, joined
 * through the hospital attribution) — never a sibling hospital, never a
 * foreign network, never the legacy/unassigned rows — while BRANCH stays
 * branch-exact and ORGANIZATION keeps the whole network plus legacy.
 * T128 proves the DTO allowlist: exactly the legacy fifteen evidence keys
 * plus the four bounded Phase 5 keys (hospitalId, transferId,
 * sourceHospitalId, destinationHospitalId); transfer context stamped only
 * on transfer rows; hospital attribution on every context-bearing row; and
 * no patient payload (the seeded patient's MRN and name never appear
 * anywhere in a response). Client-supplied hospital/transfer identifiers
 * are never accepted for widening.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "hospital.jwt.secret=disposable-test-only-audit-iso-secret-0123456789abcdef",
        "HOSPITAL_ADMIN_PASSWORD=disposable-test-only-audit-iso-password"
})
class NetworkAuditIsolationApiTest {

    private static final String PASSWORD = "disposable-test-only-audit-iso-password";

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        var db = com.mamtrex.hospital.infrastructure.PostgresContainerSupport.newIsolatedDatabase();
        Flyway.configure().locations("classpath:db/migration")
                .dataSource(db.jdbcUrl(), db.user(), db.password()).load().migrate();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
    }


    private static final ParameterizedTypeReference<List<Map<String, Object>>> ROWS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /** The exact post-US7 AuditEventView allowlist: the legacy fifteen keys plus the bounded Phase 5 keys. */
    private static final Set<String> VIEW_KEYS = Set.of(
            "id", "actor", "action", "resourceType", "resourceId", "details", "occurredAt",
            "assignmentId", "role", "scope", "organizationId", "branchId", "departmentId",
            "correlationId", "branchAttribution",
            "hospitalId", "transferId", "sourceHospitalId", "destinationHospitalId");

    @Autowired TestRestTemplate rest;
    @Autowired HospitalOrganizationRepository organizations;
    @Autowired HospitalFacilityRepository hospitals;
    @Autowired BranchRepository branches;
    @Autowired UserAccountRepository accounts;
    @Autowired ActingAssignmentRepository assignments;
    @Autowired AuditEventRepository auditEvents;
    @Autowired PatientRepository patients;
    @Autowired PasswordEncoder encoder;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private HospitalOrganization org;
    private HospitalFacility hospitalA;
    private HospitalFacility hospitalB;
    private Branch branchA1;
    private Branch branchA2;
    private Branch branchB1;
    private Branch branchC1;
    private String transferRequestId;
    private String transferAcceptId;
    private String rowLegacy;
    private String patientMrn;
    private String patientName;

    private String netAdminToken;
    private String hospitalAToken;
    private String branchC1Token;

    private void seed() {
        if (org != null) {
            return; // one seeding per test instance (JUnit fresh instance per method)
        }
        org = organizations.save(new HospitalOrganization("AUDISO-" + suffix, "Synthetic Audit Network " + suffix));
        hospitalA = hospitals.save(new HospitalFacility(org, "AUD-A-" + suffix, "Synthetic Hospital A", "Region", "UTC"));
        hospitalB = hospitals.save(new HospitalFacility(org, "AUD-B-" + suffix, "Synthetic Hospital B", "Region", "UTC"));
        var hospitalC = hospitals.save(new HospitalFacility(org, "AUD-C-" + suffix, "Synthetic Hospital C", "Region", "UTC"));
        HospitalFacility foreignHospital = hospitals.save(new HospitalFacility(
                organizations.save(new HospitalOrganization("AUDISO-OUT-" + suffix, "Foreign Network " + suffix)),
                "AUD-FOREIGN-" + suffix, "Foreign Hospital", "Region", "UTC"));

        branchA1 = branches.save(new Branch(hospitalA, "AUD-A1-" + suffix, "Synthetic A1", "Way 1"));
        branchA2 = branches.save(new Branch(hospitalA, "AUD-A2-" + suffix, "Synthetic A2", "Way 2"));
        branchB1 = branches.save(new Branch(hospitalB, "AUD-B1-" + suffix, "Synthetic B1", "Way 1"));
        branchC1 = branches.save(new Branch(hospitalC, "AUD-C1-" + suffix, "Synthetic C1", "Way 1"));
        Branch branchC2 = branches.save(new Branch(hospitalC, "AUD-C2-" + suffix, "Synthetic C2", "Way 2"));
        Branch foreignBranch = branches.save(new Branch(foreignHospital, "AUD-FB-" + suffix, "Foreign Branch", "Way 1"));

        // A real synthetic patient of branch A1; its MRN/name are the forbidden payloads.
        patientMrn = "MRN-AUDISO-" + suffix + "-CANARY";
        patientName = "Audit Iso Patient " + suffix;
        String patientId = patients.save(new Patient(branchA1, patientMrn, patientName,
                null, null, null, null, null, null)).getId().toString();

        // Acting contexts used ONLY to attribute the seeded evidence rows.
        ActingContext ctxA1 = new ActingContext("audit-iso-a1-actor", null, Role.NURSE,
                AssignmentScope.BRANCH, org.getId(), hospitalA.getId(), branchA1.getId(), null);
        ActingContext ctxA2 = new ActingContext("audit-iso-a2-actor", null, Role.ADMIN,
                AssignmentScope.BRANCH, org.getId(), hospitalA.getId(), branchA2.getId(), null);
        ActingContext ctxB1 = new ActingContext("audit-iso-b1-actor", null, Role.NURSE,
                AssignmentScope.BRANCH, org.getId(), hospitalB.getId(), branchB1.getId(), null);
        ActingContext ctxC1 = new ActingContext("audit-iso-c1-actor", null, Role.ADMIN,
                AssignmentScope.BRANCH, org.getId(), hospitalC.getId(), branchC1.getId(), null);
        ActingContext ctxC2 = new ActingContext("audit-iso-c2-actor", null, Role.NURSE,
                AssignmentScope.BRANCH, org.getId(), hospitalC.getId(), branchC2.getId(), null);
        ActingContext ctxForeign = new ActingContext("audit-iso-foreign-actor", null, Role.NURSE,
                AssignmentScope.BRANCH, foreignHospital.getOrganization().getId(),
                foreignHospital.getId(), foreignBranch.getId(), null);

        transferRequestId = UUID.randomUUID().toString();
        transferAcceptId = UUID.randomUUID().toString();

        save("CREATE", "Patient", patientId, ctxA1, "created", "audit-iso-a1-" + suffix, null);
        save("UPDATE", "Appointment", UUID.randomUUID().toString(), ctxA2, "rescheduled",
                "audit-iso-a2-" + suffix, null);
        save("CREATE", "Patient", UUID.randomUUID().toString(), ctxB1, "created",
                "audit-iso-b1-" + suffix, null);
        save("CREATE", "Department", UUID.randomUUID().toString(), ctxC1, "created",
                "audit-iso-c1-" + suffix, null);
        save("UPDATE", "Invoice", UUID.randomUUID().toString(), ctxC2, "issued",
                "audit-iso-c2-" + suffix, null);
        // Transfer evidence: REQUEST recorded under the source-hospital (A) actor,
        // ACCEPT recorded under the destination-hospital (B) actor — both bounded to
        // source A → destination B.
        save("TRANSFER_REQUEST", "TransferRequest", transferRequestId, ctxA1,
                "transfer requested (BED_SHORTAGE)", "audit-iso-tr-" + suffix, transferRequestId);
        save("TRANSFER_ACCEPT", "TransferRequest", transferAcceptId, ctxB1,
                "transfer accepted with bed reservation", "audit-iso-ta-" + suffix, transferAcceptId);
        // Legacy/bootstrap row: no acting context at all.
        rowLegacy = auditEvents.save(new AuditEvent("system", "CREATE", "HospitalOrganization",
                org.getId().toString(), "created", null, null)).getId().toString();
        // Foreign-network row: never visible to any actor of the synthetic network.
        save("CREATE", "Patient", UUID.randomUUID().toString(), ctxForeign, "created",
                "audit-iso-foreign-" + suffix, null);

        netAdminToken = login(seedAccount("audiso-net-" + suffix, Role.ADMIN,
                AssignmentScope.ORGANIZATION, org, null, null));
        hospitalAToken = login(seedAccount("audiso-hospa-" + suffix, Role.ADMIN,
                AssignmentScope.HOSPITAL, org, hospitalA, null));
        branchC1Token = login(seedAccount("audiso-brc1-" + suffix, Role.ADMIN,
                AssignmentScope.BRANCH, org, null, branchC1));
    }

    private void save(String action, String type, String resourceId, ActingContext context,
                      String details, String correlationId, String transferId) {
        AuditEvent event = new AuditEvent(context.username(), action, type, resourceId, details,
                context, correlationId);
        if (transferId != null) {
            event.stampTransferContext(UUID.fromString(transferId), hospitalA.getId(), hospitalB.getId());
        }
        auditEvents.save(event);
    }

    private String seededEventId(String correlationId) {
        return auditEvents.findAll().stream()
                .filter(event -> correlationId.equals(event.getCorrelationId()))
                .findFirst().orElseThrow().getId().toString();
    }

    private String seedAccount(String username, Role role, AssignmentScope scope,
                               HospitalOrganization organization, HospitalFacility hospital, Branch branch) {
        accounts.save(new UserAccount(username, encoder.encode(PASSWORD), Set.of(role)));
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        ActingAssignment created = switch (scope) {
            case ORGANIZATION -> ActingAssignment.organization(account, organization, role);
            case HOSPITAL -> ActingAssignment.hospital(account, organization, role, hospital);
            case BRANCH -> ActingAssignment.branch(account, organization, role, branch);
            case DEPARTMENT -> throw new IllegalArgumentException("not exercised in this matrix");
        };
        assignments.save(created);
        return username;
    }

    private String login(String username) {
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), jsonHeaders()), MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "synthetic login for " + username);
        return String.valueOf(Objects.requireNonNull(response.getBody()).get("accessToken"));
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private List<Map<String, Object>> readAudit(String token, String query) {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                "/api/audit" + (query == null ? "" : query), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ROWS);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the ADMIN audit read must succeed");
        return Objects.requireNonNull(response.getBody());
    }

    private String readAuditBody(String token) {
        ResponseEntity<String> response = rest.exchange("/api/audit", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private Set<String> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("id"))).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------
    // T128 — the DTO allowlist and bounded transfer context.
    // ------------------------------------------------------------------

    @Test
    void everyAuditRowExposesExactlyTheNineteenKeyAllowlistWithBoundedTransferContext() {
        seed();
        List<Map<String, Object>> rows = readAudit(netAdminToken, null);
        assertTrue(rows.size() >= 8, "the network slice must carry the seeded rows");
        for (Map<String, Object> row : rows) {
            assertEquals(VIEW_KEYS, row.keySet(),
                    "every audit row must carry exactly the allowlisted keys, never the entity");
        }
        Map<String, Map<String, Object>> byId = rows.stream().collect(Collectors.toMap(
                row -> String.valueOf(row.get("id")), row -> row));

        // The transfer rows carry the bounded transfer context; evidence rows do not.
        Map<String, Object> transferRequest = byId.get(seededEventId("audit-iso-tr-" + suffix));
        assertEquals(transferRequestId, String.valueOf(transferRequest.get("transferId")));
        assertEquals(hospitalA.getId().toString(), String.valueOf(transferRequest.get("sourceHospitalId")));
        assertEquals(hospitalB.getId().toString(), String.valueOf(transferRequest.get("destinationHospitalId")));
        assertEquals(hospitalA.getId().toString(), String.valueOf(transferRequest.get("hospitalId")),
                "the recording actor's hospital attributes the transfer request row");
        Map<String, Object> transferAccept = byId.get(seededEventId("audit-iso-ta-" + suffix));
        assertEquals(transferAcceptId, String.valueOf(transferAccept.get("transferId")));
        assertEquals(hospitalB.getId().toString(), String.valueOf(transferAccept.get("hospitalId")),
                "the destination actor's hospital attributes the accept row");

        Map<String, Object> patientEvent = byId.get(seededEventId("audit-iso-a1-" + suffix));
        for (String empty : List.of("transferId", "sourceHospitalId", "destinationHospitalId")) {
            assertTrue(patientEvent.get(empty) == null,
                    "a non-transfer evidence row must carry no transfer context");
        }
        assertEquals(hospitalA.getId().toString(), String.valueOf(patientEvent.get("hospitalId")),
                "a context-bearing row is attributed to its acting hospital");
        assertEquals(branchA1.getId().toString(), String.valueOf(patientEvent.get("branchId")));

        // The legacy row keeps null hospital attribution and the legacy mark.
        Map<String, Object> legacy = byId.get(rowLegacy);
        assertTrue(legacy.get("hospitalId") == null);
        assertEquals("legacy/unassigned", String.valueOf(legacy.get("branchAttribution")));

        // Forbidden payloads: the seeded patient's MRN and name never reach any response,
        // and no patient-shaped field ever enters the contract.
        String body = readAuditBody(netAdminToken);
        assertFalse(body.contains(patientMrn), "the patient MRN is never an audit payload");
        assertFalse(body.contains(patientName), "the patient name is never an audit payload");
        for (String forbiddenKey : List.of("medicalRecordNumber", "fullName", "dateOfBirth")) {
            assertFalse(body.contains("\"" + forbiddenKey + "\""),
                    () -> "no patient-shaped field may enter the audit contract: " + forbiddenKey);
        }
    }

    // ------------------------------------------------------------------
    // T127 — the hospital-wide read matrix.
    // ------------------------------------------------------------------

    @Test
    void hospitalScopeReadsItsWholeHospitalAndNeverBeyond() {
        seed();
        List<Map<String, Object>> rows = readAudit(hospitalAToken, null);
        Set<String> visible = ids(rows);
        assertEquals(Set.of(seededEventId("audit-iso-a1-" + suffix), seededEventId("audit-iso-a2-" + suffix),
                        seededEventId("audit-iso-tr-" + suffix)),
                visible,
                "a HOSPITAL-scoped ADMIN reads every evidence row of the acting hospital "
                        + "(both branches, its own transfer recordings) and nothing else");
        String body = readAuditBody(hospitalAToken);
        // The bounded transfer destination (hospitalB) is FR-026 evidence on the
        // A-actor's own transfer row; a leak would be hospital B's ATTRIBUTED
        // evidence: its branch ids, its own audit rows, its hospital attribution.
        assertFalse(body.contains(branchB1.getId().toString()));
        assertFalse(body.contains("audit-iso-b1-" + suffix), "hospital B's own rows never appear");
        assertTrue(rows.stream().noneMatch(row -> hospitalB.getId().toString()
                        .equals(String.valueOf(row.get("hospitalId")))),
                "no row attributed to the sibling hospital may enter the hospital slice");
        assertFalse(body.contains("legacy/unassigned"), "legacy rows stay an organization-scope slice");
        assertFalse(body.contains("audit-iso-foreign-" + suffix), "foreign-network rows never appear");
    }

    @Test
    void branchScopeStaysBranchExactInsideItsHospital() {
        seed();
        assertEquals(Set.of(seededEventId("audit-iso-c1-" + suffix)), ids(readAudit(branchC1Token, null)),
                "a BRANCH-scoped ADMIN reads exactly its own branch's rows — never the sibling branch");
    }

    @Test
    void networkScopeKeepsTheWholeOrganizationPlusLegacy() {
        seed();
        Set<String> visible = ids(readAudit(netAdminToken, null));
        for (String expected : List.of("audit-iso-a1-", "audit-iso-a2-", "audit-iso-b1-",
                "audit-iso-c1-", "audit-iso-c2-", "audit-iso-tr-", "audit-iso-ta-")) {
            assertTrue(visible.contains(seededEventId(expected + suffix)),
                    () -> "the organization slice must include " + expected);
        }
        assertTrue(visible.contains(rowLegacy), "legacy rows stay visible to the organization scope");
        assertFalse(visible.toString().contains("audit-iso-foreign-" + suffix),
                "the foreign-network row never enters the organization slice");
    }

    @Test
    void filtersNarrowInsideTheSliceAndForeignIdentifiersNeverWiden() {
        seed();
        // Branch filter narrows inside the hospital slice.
        assertEquals(Set.of(seededEventId("audit-iso-a2-" + suffix)),
                ids(readAudit(hospitalAToken, "?branchId=" + branchA2.getId())));
        // A foreign branch filter answers the empty set — never a widened list.
        assertTrue(readAudit(hospitalAToken, "?branchId=" + branchB1.getId()).isEmpty(),
                "a foreign branch filter must answer the empty set for a hospital slice");
        // Client-supplied hospital/transfer identifiers are ignored (not filter
        // names at all); a foreign branch filter stays a real narrowing filter
        // and cannot be disguised into a widening one by adding them.
        String plain = readAuditBody(hospitalAToken);
        assertFalse(plain.isBlank(), "the hospital slice must be non-empty for the filter matrix");
        ResponseEntity<String> decorated = rest.exchange("/api/audit?hospitalId=" + hospitalB.getId()
                        + "&transferId=" + transferAcceptId + "&branchId=" + branchB1.getId(),
                HttpMethod.GET, new HttpEntity<>(bearer(hospitalAToken)), String.class);
        assertEquals(HttpStatus.OK, decorated.getStatusCode());
        assertTrue(decorated.getBody().replace("[]", "").isBlank(),
                "hospital/transfer query names are ignored; the foreign branch filter still narrows to empty");
        // The correlation filter still narrows exactly.
        assertEquals(Set.of(seededEventId("audit-iso-tr-" + suffix)),
                ids(readAudit(hospitalAToken, "?correlationId=audit-iso-tr-" + suffix)));
    }

    @Test
    void malformedBranchFilterIsTheSharedTypedBadRequest() {
        seed();
        ResponseEntity<String> response = rest.exchange("/api/audit?branchId=not-a-uuid", HttpMethod.GET,
                new HttpEntity<>(bearer(hospitalAToken)), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "a malformed typed filter keeps the shared 400 boundary");
    }
}

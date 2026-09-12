package com.mamtrex.hospital.operations;

import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Care-operations contract suite. Characterized the raw baseline in docs/plan2.md
 * Task 1 (packet MEDICORE-PLAN2-TASK1-030), updated by Task 2 (packet
 * MEDICORE-PLAN2-TASK2-031) for admissions, and updated by Task 3 (packet
 * MEDICORE-PLAN2-TASK3-032) for emergency visits, and updated by Task 4
 * (packet MEDICORE-PLAN2-TASK4-033) for invoices: the admissions,
 * emergency-visit, and invoice tests now pin the normalized workflows
 * (verified patient
 * references, DTO responses without persistence metadata, server-owned
 * lifecycles with 409 on repeat/invalid transitions, exactly one audit event
 * per successful mutation and none on failures), while the remaining families
 * still deliberately freeze TODAY'S raw behavior as later change targets —
 *
 * - the bed controller still returns JPA entities directly, so responses
 *   expose the persistence metadata id/createdAt/updatedAt/version (later:
 *   DTOs; invoices, admissions, and emergency visits already return the
 *   normalized DTO);
 * - bed request fields are still raw @NotBlank Strings stored verbatim —
 *   raw statuses and nonexistent patientId values are all accepted there
 *   (later: verified references, typed values, per-domain status sets);
 * - invoices now own the Task 4 normalized contract: a verified patient
 *   reference, six-field DTO responses, unique invoice numbers with the
 *   safe shared 409 on duplicates, validated non-negative demo amounts
 *   (canonical plain-string storage) and [A-Z]{3} demo currency labels,
 *   and PUT /api/invoices/{id}/status with the server-owned lifecycle
 *   DRAFT -> ISSUED | VOID and ISSUED -> PAID | VOID (PAID/VOID terminal);
 *   the whole family stays a FINANCIAL SIMULATION — no real payments;
 * - emergency visits now own PUT /api/emergency-visits/{id}/status with the
 *   Task 3 lifecycle WAITING -> IN_TREATMENT | CLOSED, IN_TREATMENT ->
 *   CLOSED, CLOSED terminal (the triage label stays a neutral 1-5 demo value
 *   with no clinical meaning); admissions own PUT /api/admissions/{id}/status;
 * - the dashboard exposes exactly the eleven Task 5 keys — the five
 *   whole-table totals plus status-aware aggregates (beds add no key);
 * - the RBAC family rules are unchanged by Tasks 2, 3, and 4: DOCTOR and NURSE
 *   keep live admissions/emergency writes (plan2 §7.1 write-role narrowing is
 *   an owner decision), and BILLING is isolated to invoices with 403 elsewhere.
 *
 * Runs against an isolated in-memory H2 database (never the production file
 * store) with disposable synthetic test-only secrets and fabricated record
 * values; no real personal or clinical data is ever used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:care-ops-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + CareOperationsApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + CareOperationsApiTest.TEST_ACCOUNT_PASSWORD
})
class CareOperationsApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-careops-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-careops-01";

    private static final String ADMIN_USER = "careops-admin";
    private static final String BILLING_USER = "careops-billing";
    private static final String DOCTOR_USER = "careops-doctor";
    private static final String NURSE_USER = "careops-nurse";
    private static final String RECEPTIONIST_USER = "careops-receptionist";

    /** Legacy entity-shaped response contract: persistence metadata leaks. */
    private static final Set<String> METADATA_KEYS = Set.of("id", "createdAt", "updatedAt", "version");

    /** Task 2 DTO contract: exactly these six fields, no persistence metadata. */
    private static final Set<String> ADMISSION_DTO_FIELDS = Set.of(
            "id", "patientId", "admittedAt", "dischargedAt", "reason", "status");

    /** Task 3 DTO contract: exactly these six fields, no persistence metadata. */
    private static final Set<String> EMERGENCY_VISIT_DTO_FIELDS = Set.of(
            "id", "patientId", "arrivalAt", "triageLevel", "chiefComplaint", "status");

    /** Task 4 DTO contract: exactly these six fields, no persistence metadata. */
    private static final Set<String> INVOICE_DTO_FIELDS = Set.of(
            "id", "patientId", "invoiceNumber", "amount", "currency", "status");

    /** Task 6 DTO contract: branch-owned fields only, no persistence metadata or patient reference. */
    private static final Set<String> BED_DTO_FIELDS = Set.of(
            "id", "branchId", "ward", "room", "bedNumber", "occupancyStatus");

    /** Task 5 dashboard contract: totals plus status-aware aggregates, exactly these eleven keys. */
    private static final Set<String> DASHBOARD_KEYS = Set.of(
            "patients", "appointments", "admissions", "emergencyVisits", "invoices",
            "openAdmissions", "activeEmergencyVisits",
            "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid");

    /** Task 7 audit contract: exactly these six public event fields. */
    private static final Set<String> AUDIT_CONTRACT_KEYS = Set.of(
            "actor", "action", "resourceType", "resourceId", "details", "occurredAt");

    /** Task 7 tolerated persistence metadata already on the entity JSON; the contract never grows beyond this. */
    private static final Set<String> AUDIT_METADATA_KEYS = Set.of("id", "createdAt", "updatedAt", "version");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private static final String TEST_ORG_CODE = "CAREOPS-ORG";
    private static final String TEST_BRANCH_CODE = "CAREOPS-BR-DEFAULT";

    private UserAccount account(String username, Role role) {
        return new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(role));
    }

    private void record(UserAccount account) {
        if (accounts.findByUsername(account.getUsername()).isEmpty()) {
            accounts.save(account);
        }
    }

    /**
     * Task 3 seeding: every synthetic account logs in through an explicit
     * enabled acting assignment on a stable synthetic organization and
     * default branch — no global-role fallback exists.
     */
    @BeforeEach
    void seedDisposableAccounts() {
        record(account(ADMIN_USER, Role.ADMIN));
        record(account(BILLING_USER, Role.BILLING));
        record(account(DOCTOR_USER, Role.DOCTOR));
        record(account(NURSE_USER, Role.NURSE));
        record(account(RECEPTIONIST_USER, Role.RECEPTIONIST));
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Synthetic CareOps Hospital")));
        Branch branch = branches.findByOrganizationIdAndCode(org.getId(), TEST_BRANCH_CODE).orElseGet(() ->
                branches.save(new Branch(org, TEST_BRANCH_CODE, "Synthetic CareOps Branch", "1 CareOps Way")));
        ensureAssignment(ADMIN_USER, Role.ADMIN, AssignmentScope.ORGANIZATION, org, null);
        ensureAssignment(BILLING_USER, Role.BILLING, AssignmentScope.BRANCH, org, branch);
        ensureAssignment(DOCTOR_USER, Role.DOCTOR, AssignmentScope.BRANCH, org, branch);
        ensureAssignment(NURSE_USER, Role.NURSE, AssignmentScope.BRANCH, org, branch);
        ensureAssignment(RECEPTIONIST_USER, Role.RECEPTIONIST, AssignmentScope.BRANCH, org, branch);
    }

    private void ensureAssignment(String username, Role role, AssignmentScope scope,
                                  HospitalOrganization org, Branch branch) {
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        boolean present = switch (scope) {
            case ORGANIZATION -> assignments
                    .findByAccountIdAndRoleAndScopeAndBranchIsNullAndDepartmentIsNull(account.getId(), role, scope)
                    .isPresent();
            case BRANCH -> assignments
                    .findByAccountIdAndRoleAndScopeAndBranchId(account.getId(), role, scope, branch.getId())
                    .isPresent();
            case DEPARTMENT -> false;
        };
        if (!present) {
            assignments.save(switch (scope) {
                case ORGANIZATION -> ActingAssignment.organization(account, org, role);
                case BRANCH -> ActingAssignment.branch(account, org, role, branch);
                case DEPARTMENT -> throw new IllegalArgumentException("These suites seed organization/branch scopes only");
            });
        }
    }

    // ------------------------------------------------------------------
    // Authorization baseline (docs/plan2.md Task 1 contract item d)
    // ------------------------------------------------------------------

    /** Anonymous callers hit the established 401 boundary on every family. */
    @Test
    void anonymousRequestsReceiveUnauthorizedAcrossCareOperationsFamilies() {
        for (String path : List.of("/api/admissions", "/api/emergency-visits",
                "/api/invoices", "/api/beds", "/api/dashboard")) {
            assertEquals(HttpStatus.UNAUTHORIZED, getStatus(path, null).getStatusCode(),
                    "anonymous GET " + path + " must stay on the 401 boundary");
        }
    }

    /**
     * Invoice RBAC baseline: only ADMIN and BILLING reach /api/invoices/**;
     * RECEPTIONIST, DOCTOR, and NURSE are refused with 403, and their refused
     * create attempts must not persist any invoice.
     */
    @Test
    void invoiceFamilyAllowsOnlyAdminAndBilling() {
        assertEquals(HttpStatus.OK, getStatus("/api/invoices", login(ADMIN_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, getStatus("/api/invoices", login(BILLING_USER)).getStatusCode(),
                "BILLING is the second invoice role today and must be admitted");

        for (String username : List.of(RECEPTIONIST_USER, DOCTOR_USER, NURSE_USER)) {
            assertEquals(HttpStatus.FORBIDDEN, getStatus("/api/invoices", login(username)).getStatusCode(),
                    username + " must be refused the invoice read with 403");
        }

        long invoicesBefore = dashboardCount("invoices", login(ADMIN_USER));
        for (String username : List.of(RECEPTIONIST_USER, DOCTOR_USER, NURSE_USER)) {
            assertEquals(HttpStatus.FORBIDDEN, post("/api/invoices", login(username), invoicePayload(username)).getStatusCode(),
                    username + " must be refused the invoice create with 403");
        }
        assertEquals(invoicesBefore, dashboardCount("invoices", login(ADMIN_USER)),
                "a 403 invoice-create attempt must not persist a record");
    }

    /**
     * Family-rule baseline: admissions, emergency visits, and beds admit the
     * four clinical-administrative roles on reads while BILLING is refused,
     * and the dashboard stays open to every authenticated role (BILLING
     * included). Task 2 changed no server role policy, so the family rules
     * still carry NO method distinction — NURSE holds a live admission write
     * (plan2 §7.1 decision pending).
     */
    @Test
    void admissionEmergencyAndBedFamiliesAllowFourRolesWhileBillingIsIsolated() {
        List<String> familyPaths = List.of("/api/admissions", "/api/emergency-visits", "/api/beds");
        for (String path : familyPaths) {
            for (String username : List.of(ADMIN_USER, DOCTOR_USER, NURSE_USER, RECEPTIONIST_USER)) {
                assertEquals(HttpStatus.OK, getStatus(path, login(username)).getStatusCode(),
                        username + " holds the " + path + " family role and must be admitted");
            }
            assertEquals(HttpStatus.FORBIDDEN, getStatus(path, login(BILLING_USER)).getStatusCode(),
                    "BILLING must be refused " + path + " with 403");
        }
        for (String username : List.of(ADMIN_USER, DOCTOR_USER, NURSE_USER, RECEPTIONIST_USER, BILLING_USER)) {
            assertEquals(HttpStatus.OK, getStatus("/api/dashboard", login(username)).getStatusCode(),
                    "the dashboard must stay reachable for every authenticated role including " + username);
        }
    }

    /**
     * Current permissive write policy: the admissions family rule has no
     * method-level narrowing, so NURSE can create and delete admissions —
     * now through the normalized Task 2 create contract, which requires a
     * verified patient reference. Pinned because plan2 §7.1 leaves narrowing
     * to an owner decision; if that decision lands, this test is the visible
     * tripwire.
     */
    @Test
    void nurseCurrentlyHoldsAdmissionAndEmergencyWritesUnderFamilyRule() {
        String adminToken = login(ADMIN_USER);
        String patientId = createSyntheticPatient(adminToken, "nurse-write");
        String nurseToken = login(NURSE_USER);

        ResponseEntity<Map<String, Object>> admitted = post("/api/admissions", nurseToken,
                admissionCreatePayload("nurse", patientId));
        assertEquals(HttpStatus.OK, admitted.getStatusCode(),
                "NURSE must currently be able to write admissions (no method narrowing exists)");
        String admissionId = requireId(admitted);

        ResponseEntity<Map<String, Object>> visited = post("/api/emergency-visits", nurseToken,
                emergencyCreatePayload("nurse", patientId));
        assertEquals(HttpStatus.OK, visited.getStatusCode(),
                "NURSE must currently be able to write emergency visits (no method narrowing exists)");
        String visitId = requireId(visited);

        assertEquals(HttpStatus.OK, delete("/api/admissions/" + admissionId, nurseToken).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, nurseToken).getStatusCode());
    }

    // ------------------------------------------------------------------
    // Admission workflow contract (docs/plan2.md Task 2 — normalized)
    // ------------------------------------------------------------------

    /**
     * Task 2 create contract: a verified patient reference (unknown patient
     * is the shared safe 404), typed ISO admittedAt stored as its canonical
     * string, a non-blank reason, and server-owned lifecycle state — the
     * response is exactly the six-field DTO with status ADMITTED and
     * dischargedAt unset, and client-sent status/dischargedAt values never
     * reach storage. Detail and list share the same DTO contract with no
     * persistence metadata anywhere.
     */
    @Test
    void admissionCreatePinsNormalizedDtoContractWithVerifiedPatientReference() {
        String token = login(ADMIN_USER);
        String patientId = createSyntheticPatient(token, "contract");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("admittedAt", "2031-01-01T08:15:30");
        payload.put("reason", "synthetic normalized admission " + suffix);
        // Not part of the create contract: the server owns both lifecycle
        // fields, so these client values must be ignored, never stored.
        payload.put("dischargedAt", "2031-02-01T10:00:00");
        payload.put("status", "WHENEVER-RAW");

        ResponseEntity<Map<String, Object>> created = post("/api/admissions", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode(), "a verified patient must admit successfully");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(ADMISSION_DTO_FIELDS, body.keySet(),
                "the admission response must be exactly the DTO contract with no persistence metadata");
        assertEquals(patientId, body.get("patientId"),
                "patientId must be the canonical UUID string of the verified patient");
        assertEquals("2031-01-01T08:15:30", body.get("admittedAt"), "admittedAt must be the canonical ISO string");
        assertEquals("synthetic normalized admission " + suffix, body.get("reason"));
        assertEquals("ADMITTED", body.get("status"), "the server must set status=ADMITTED, never a client value");
        assertNull(body.get("dischargedAt"), "dischargedAt must stay unset at creation");
        String admissionId = requireId(created);

        ResponseEntity<Map<String, Object>> detail = getMap("/api/admissions/" + admissionId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertNotNull(detail.getBody());
        assertEquals(ADMISSION_DTO_FIELDS, detail.getBody().keySet(), "detail must match the same DTO contract");
        assertEquals(patientId, detail.getBody().get("patientId"));
        assertEquals("ADMITTED", detail.getBody().get("status"));
        assertNull(detail.getBody().get("dischargedAt"));

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/admissions", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "admission list must carry a body");
        List<Map<String, Object>> mine = listBody.stream()
                .filter(a -> admissionId.equals(String.valueOf(a.get("id"))))
                .collect(Collectors.toList());
        assertEquals(1, mine.size(), "the created admission must appear exactly once in the list");
        assertEquals(ADMISSION_DTO_FIELDS, mine.get(0).keySet(), "every list item must match the DTO contract");

        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/admissions/not-a-uuid", token).getStatusCode(),
                "a malformed admission UUID path must be a client error, never a 500");
    }

    /**
     * Task 2 failure contracts: an unknown patient is the shared safe 404, a
     * non-UUID patientId or an unparseable admittedAt is a malformed body
     * 400, and missing/blank required fields are validation 400s — none of
     * them may persist an admission.
     */
    @Test
    void admissionCreateRejectsUnknownPatientAndMalformedBodiesWithoutPersisting() {
        String token = login(ADMIN_USER);
        long admissionsBefore = dashboardCount("admissions", token);

        Map<String, Object> unknownPatient = admissionCreatePayload("unknown", UUID.randomUUID().toString());
        ResponseEntity<Map<String, Object>> notFound = post("/api/admissions", token, unknownPatient);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode(),
                "an unknown patient reference must return the shared 404, never persist");
        Map<String, Object> notFoundBody = notFound.getBody();
        assertNotNull(notFoundBody, "the 404 must carry the shared ApiError body");
        assertEquals(404, ((Number) notFoundBody.get("status")).intValue(), "ApiError.status must echo 404");
        assertEquals("Not Found", notFoundBody.get("error"));

        Map<String, Object> nonUuidPatient = admissionCreatePayload("nonuuid", "not-a-uuid");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admissions", token, nonUuidPatient).getStatusCode(),
                "a non-UUID patientId fails typed deserialization as a malformed body");

        Map<String, Object> badTimestamp = admissionCreatePayload("badtime", null);
        badTimestamp.put("admittedAt", "not-a-timestamp-" + suffix);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admissions", token, badTimestamp).getStatusCode(),
                "an unparseable admittedAt fails typed deserialization as a malformed body");

        Map<String, Object> blankReason = admissionCreatePayload("blank-reason", null);
        blankReason.put("reason", "   ");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admissions", token, blankReason).getStatusCode(),
                "a blank reason must be rejected by validation");

        Map<String, Object> missingPatient = new LinkedHashMap<>();
        missingPatient.put("admittedAt", "2031-01-01T08:15:30");
        missingPatient.put("reason", "synthetic missing reference " + suffix);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admissions", token, missingPatient).getStatusCode(),
                "a missing patientId must be rejected by validation");

        Map<String, Object> missingAdmittedAt = new LinkedHashMap<>();
        missingAdmittedAt.put("patientId", UUID.randomUUID().toString());
        missingAdmittedAt.put("reason", "synthetic missing timestamp " + suffix);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admissions", token, missingAdmittedAt).getStatusCode(),
                "a missing admittedAt must be rejected by validation");

        assertEquals(admissionsBefore, dashboardCount("admissions", token),
                "no rejected create may persist an admission");
    }

    /**
     * Task 2 discharge lifecycle: PUT /api/admissions/{id}/status with
     * {"status":"DISCHARGED"} is legal only from ADMITTED, the server stamps
     * dischargedAt itself, and the response is the same six-field DTO.
     * Repeating the discharge or requesting any other transition is the safe
     * shared 409 with a controlled message; failed operations produce no
     * audit events, and the successful create and discharge produce exactly
     * one audit event each with the session actor.
     */
    @Test
    void admissionDischargePinsServerStampedTransitionConflictAndAudit() {
        String token = login(RECEPTIONIST_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "discharge");
        String admissionId = requireId(post("/api/admissions", token, admissionCreatePayload("discharge", patientId)));

        List<Map<String, Object>> beforeDischarge = auditEvents(login(ADMIN_USER));
        assertSingleEvent(beforeDischarge, "Admission", admissionId, "CREATE", RECEPTIONIST_USER, "created");

        ResponseEntity<Map<String, Object>> discharged =
                put("/api/admissions/" + admissionId + "/status", token, Map.of("status", "DISCHARGED"));
        assertEquals(HttpStatus.OK, discharged.getStatusCode(), "discharging an ADMITTED admission must succeed");
        Map<String, Object> body = discharged.getBody();
        assertNotNull(body);
        assertEquals(ADMISSION_DTO_FIELDS, body.keySet(), "the discharge response must stay on the DTO contract");
        assertEquals("DISCHARGED", body.get("status"), "the server must set status=DISCHARGED");
        assertNotNull(body.get("dischargedAt"), "the server must stamp the discharge time itself");
        assertEquals(patientId, body.get("patientId"), "discharge must not change the verified reference");
        assertEquals("2031-01-01T08:15:30", body.get("admittedAt"), "discharge must not change admittedAt");

        List<Map<String, Object>> afterDischarge = auditEvents(login(ADMIN_USER));
        assertSingleEvent(afterDischarge, "Admission", admissionId, "UPDATE", RECEPTIONIST_USER, "status: DISCHARGED");

        ResponseEntity<Map<String, Object>> repeat =
                put("/api/admissions/" + admissionId + "/status", token, Map.of("status", "DISCHARGED"));
        assertEquals(HttpStatus.CONFLICT, repeat.getStatusCode(),
                "repeating the discharge of an already-discharged admission must return the shared 409");
        Map<String, Object> conflict = repeat.getBody();
        assertNotNull(conflict, "the 409 must carry the shared ApiError body");
        assertEquals(409, ((Number) conflict.get("status")).intValue(), "ApiError.status must echo 409");
        assertEquals("Conflict", conflict.get("error"));
        String conflictMessage = String.valueOf(conflict.get("message"));
        assertTrue(conflictMessage.contains("cannot be discharged"),
                "the 409 message must be controlled and client-safe, never an entity or stack dump");
        assertFalse(conflictMessage.contains("Exception"), "the 409 message must not leak exception internals");

        assertEquals(HttpStatus.CONFLICT,
                put("/api/admissions/" + admissionId + "/status", token, Map.of("status", "READMITTED")).getStatusCode(),
                "any transition other than DISCHARGED is not in the admission transition map and must 409");

        String unknownId = UUID.randomUUID().toString();
        ResponseEntity<Map<String, Object>> unknown =
                put("/api/admissions/" + unknownId + "/status", token, Map.of("status", "DISCHARGED"));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode(),
                "discharging an unknown admission must be the shared 404");

        List<Map<String, Object>> afterFailures = auditEvents(login(ADMIN_USER));
        assertSingleEvent(afterFailures, "Admission", admissionId, "CREATE", RECEPTIONIST_USER, "created");
        assertSingleEvent(afterFailures, "Admission", admissionId, "UPDATE", RECEPTIONIST_USER, "status: DISCHARGED");
    }

    /**
     * Delete stays service-owned and audited under the normalized contract:
     * 404-safe on repeat, the shared ApiError body afterwards, and exactly
     * one DELETE audit event with the session actor.
     */
    @Test
    void admissionDeleteRemainsServiceOwnedSafeAndAudited() {
        String token = login(NURSE_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "delete");
        String admissionId = requireId(post("/api/admissions", token, admissionCreatePayload("delete", patientId)));

        assertEquals(HttpStatus.OK, delete("/api/admissions/" + admissionId, token).getStatusCode());
        ResponseEntity<String> gone = getStatus("/api/admissions/" + admissionId, token);
        assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode(), "get after delete must be 404");
        Map<String, Object> error = parseError(gone);
        assertEquals(Set.of("timestamp", "status", "error", "message", "path"), error.keySet(),
                "404 must use the shared ApiError contract shape exactly");
        assertEquals(404, ((Number) error.get("status")).intValue(), "ApiError.status must echo 404");
        assertEquals("Not Found", error.get("error"));
        assertEquals("/api/admissions/" + admissionId, error.get("path"));
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/admissions/" + admissionId, token).getStatusCode(),
                "deleting an unknown admission must be 404, not a silent success");

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "Admission", admissionId, "DELETE", NURSE_USER, "deleted");
    }

    // ------------------------------------------------------------------
    // Emergency-visit workflow contract (docs/plan2.md Task 3 — normalized)
    // ------------------------------------------------------------------

    /**
     * Task 3 create contract: a verified patient reference (unknown patient
     * is the shared safe 404), typed ISO arrivalAt stored as its canonical
     * string, a neutral 1–5 triage demo label with NO clinical meaning, and a
     * non-blank synthetic chief complaint — the response is exactly the
     * six-field DTO with server-owned status WAITING, and a client-sent
     * status value never reaches storage. Detail and list share the same DTO
     * contract with no persistence metadata anywhere.
     */
    @Test
    void emergencyVisitCreatePinsNormalizedDtoContractWithVerifiedPatientReference() {
        // The patient fixture is ADMIN-created; DOCTOR registering the visit
        // pins the four-role family write on the normalized contract.
        String token = login(DOCTOR_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "contract");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("arrivalAt", "2031-01-01T09:15:00");
        payload.put("triageLevel", "3");
        payload.put("chiefComplaint", "synthetic normalized complaint " + suffix);
        // Not part of the create contract: the server owns the lifecycle, so
        // this client value must be ignored, never stored.
        payload.put("status", "WHENEVER-RAW");

        ResponseEntity<Map<String, Object>> created = post("/api/emergency-visits", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode(), "a verified patient must register a visit successfully");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(EMERGENCY_VISIT_DTO_FIELDS, body.keySet(),
                "the emergency-visit response must be exactly the DTO contract with no persistence metadata");
        assertEquals(patientId, body.get("patientId"),
                "patientId must be the canonical UUID string of the verified patient");
        assertEquals("2031-01-01T09:15", body.get("arrivalAt"), "arrivalAt must be the canonical ISO string");
        assertEquals("3", body.get("triageLevel"), "triageLevel must be the neutral demo label, stored canonically");
        assertEquals("synthetic normalized complaint " + suffix, body.get("chiefComplaint"));
        assertEquals("WAITING", body.get("status"), "the server must set status=WAITING, never a client value");
        String visitId = requireId(created);

        ResponseEntity<Map<String, Object>> detail = getMap("/api/emergency-visits/" + visitId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertNotNull(detail.getBody());
        assertEquals(EMERGENCY_VISIT_DTO_FIELDS, detail.getBody().keySet(), "detail must match the same DTO contract");
        assertEquals(patientId, detail.getBody().get("patientId"));
        assertEquals("WAITING", detail.getBody().get("status"));

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/emergency-visits", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "emergency list must carry a body");
        List<Map<String, Object>> mine = listBody.stream()
                .filter(v -> visitId.equals(String.valueOf(v.get("id"))))
                .collect(Collectors.toList());
        assertEquals(1, mine.size(), "the created visit must appear exactly once in the list");
        assertEquals(EMERGENCY_VISIT_DTO_FIELDS, mine.get(0).keySet(), "every list item must match the DTO contract");

        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/emergency-visits/not-a-uuid", token).getStatusCode(),
                "a malformed visit UUID path must be a client error, never a 500");
    }

    /**
     * Task 3 failure contracts: an unknown patient is the shared safe 404, a
     * non-UUID patientId or an unparseable arrivalAt is a malformed body 400,
     * a triage label outside the neutral 1–5 demo set is a validation 400,
     * and missing/blank required fields are validation 400s — none of them
     * may persist a visit.
     */
    @Test
    void emergencyVisitCreateRejectsUnknownPatientAndInvalidTriageWithoutPersisting() {
        String token = login(DOCTOR_USER);
        long visitsBefore = dashboardCount("emergencyVisits", token);

        Map<String, Object> unknownPatient = emergencyCreatePayload("unknown", UUID.randomUUID().toString());
        ResponseEntity<Map<String, Object>> notFound = post("/api/emergency-visits", token, unknownPatient);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode(),
                "an unknown patient reference must return the shared 404, never persist");
        Map<String, Object> notFoundBody = notFound.getBody();
        assertNotNull(notFoundBody, "the 404 must carry the shared ApiError body");
        assertEquals(404, ((Number) notFoundBody.get("status")).intValue(), "ApiError.status must echo 404");
        assertEquals("Not Found", notFoundBody.get("error"));

        Map<String, Object> nonUuidPatient = emergencyCreatePayload("nonuuid", "not-a-uuid");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/emergency-visits", token, nonUuidPatient).getStatusCode(),
                "a non-UUID patientId fails typed deserialization as a malformed body");

        Map<String, Object> badTimestamp = emergencyCreatePayload("badtime", null);
        badTimestamp.put("arrivalAt", "yesterday-ish");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/emergency-visits", token, badTimestamp).getStatusCode(),
                "an unparseable arrivalAt fails typed deserialization as a malformed body");

        for (String rejected : List.of("9-out-of-5", "0", "6", "3.5", " 3 ", "")) {
            Map<String, Object> badTriage = emergencyCreatePayload("triage-" + rejected.hashCode(), null);
            badTriage.put("triageLevel", rejected);
            assertEquals(HttpStatus.BAD_REQUEST,
                    post("/api/emergency-visits", token, badTriage).getStatusCode(),
                    "a triage label outside the neutral 1–5 demo set must be rejected: '" + rejected + "'");
        }

        Map<String, Object> missingTriage = emergencyCreatePayload("missing-triage", null);
        missingTriage.remove("triageLevel");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/emergency-visits", token, missingTriage).getStatusCode(),
                "a missing triageLevel must be rejected by validation");

        Map<String, Object> blankComplaint = emergencyCreatePayload("blank-complaint", null);
        blankComplaint.put("chiefComplaint", "   ");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/emergency-visits", token, blankComplaint).getStatusCode(),
                "a blank chiefComplaint must be rejected by validation");

        Map<String, Object> missingPatient = new LinkedHashMap<>();
        missingPatient.put("arrivalAt", "2031-01-01T09:15:00");
        missingPatient.put("triageLevel", "3");
        missingPatient.put("chiefComplaint", "synthetic missing reference " + suffix);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/emergency-visits", token, missingPatient).getStatusCode(),
                "a missing patientId must be rejected by validation");

        Map<String, Object> missingArrival = new LinkedHashMap<>();
        missingArrival.put("patientId", UUID.randomUUID().toString());
        missingArrival.put("triageLevel", "3");
        missingArrival.put("chiefComplaint", "synthetic missing timestamp " + suffix);
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/emergency-visits", token, missingArrival).getStatusCode(),
                "a missing arrivalAt must be rejected by validation");

        assertEquals(visitsBefore, dashboardCount("emergencyVisits", token),
                "no rejected create may persist an emergency visit");
    }

    /**
     * Task 3 transition lifecycle: PUT /api/emergency-visits/{id}/status
     * permits exactly WAITING -> IN_TREATMENT | CLOSED and
     * IN_TREATMENT -> CLOSED; CLOSED is terminal. The response is the same
     * six-field DTO with the server-applied status. Repeats, backward moves,
     * and unknown targets are the safe shared 409 with a controlled message;
     * failed operations produce no audit events, and the successful create
     * and each transition produce exactly one audit event with the session
     * actor.
     */
    @Test
    void emergencyVisitTransitionsPinLifecycleConflictsAndAudit() {
        String token = login(RECEPTIONIST_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "transition");
        String visitId = requireId(post("/api/emergency-visits", token, emergencyCreatePayload("transition", patientId)));

        List<Map<String, Object>> beforeTransitions = auditEvents(login(ADMIN_USER));
        assertSingleEvent(beforeTransitions, "EmergencyVisit", visitId, "CREATE", RECEPTIONIST_USER, "created");

        ResponseEntity<Map<String, Object>> inTreatment =
                put("/api/emergency-visits/" + visitId + "/status", token, Map.of("status", "IN_TREATMENT"));
        assertEquals(HttpStatus.OK, inTreatment.getStatusCode(),
                "WAITING -> IN_TREATMENT must be a legal transition");
        Map<String, Object> treated = inTreatment.getBody();
        assertNotNull(treated);
        assertEquals(EMERGENCY_VISIT_DTO_FIELDS, treated.keySet(), "the transition response must stay on the DTO contract");
        assertEquals("IN_TREATMENT", treated.get("status"), "the server must apply the requested legal transition");
        assertEquals(patientId, treated.get("patientId"), "transitions must not change the verified reference");
        assertEquals("2031-01-01T09:15", treated.get("arrivalAt"), "transitions must not change arrivalAt");
        assertEquals("3", treated.get("triageLevel"), "transitions must not change the triage label");

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "EmergencyVisit", visitId, "UPDATE", RECEPTIONIST_USER, "status: IN_TREATMENT");

        ResponseEntity<Map<String, Object>> closed =
                put("/api/emergency-visits/" + visitId + "/status", token, Map.of("status", "CLOSED"));
        assertEquals(HttpStatus.OK, closed.getStatusCode(), "IN_TREATMENT -> CLOSED must be a legal transition");
        assertNotNull(closed.getBody());
        assertEquals("CLOSED", closed.getBody().get("status"));

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "EmergencyVisit", visitId, "UPDATE", RECEPTIONIST_USER, "status: CLOSED");

        ResponseEntity<Map<String, Object>> repeat =
                put("/api/emergency-visits/" + visitId + "/status", token, Map.of("status", "CLOSED"));
        assertEquals(HttpStatus.CONFLICT, repeat.getStatusCode(),
                "repeating a transition on a CLOSED visit must return the shared 409");
        Map<String, Object> conflict = repeat.getBody();
        assertNotNull(conflict, "the 409 must carry the shared ApiError body");
        assertEquals(409, ((Number) conflict.get("status")).intValue(), "ApiError.status must echo 409");
        assertEquals("Conflict", conflict.get("error"));
        String conflictMessage = String.valueOf(conflict.get("message"));
        assertTrue(conflictMessage.contains("CLOSED"),
                "the 409 message must name the terminal state, never an entity or stack dump");
        assertFalse(conflictMessage.contains("Exception"), "the 409 message must not leak exception internals");

        assertEquals(HttpStatus.CONFLICT,
                put("/api/emergency-visits/" + visitId + "/status", token, Map.of("status", "IN_TREATMENT")).getStatusCode(),
                "a CLOSED visit is terminal: every further transition must 409");

        // WAITING -> CLOSED is legal directly; repeating it is not.
        String directCloseId = requireId(post("/api/emergency-visits", token,
                emergencyCreatePayload("direct-close", patientId)));
        assertEquals(HttpStatus.OK,
                put("/api/emergency-visits/" + directCloseId + "/status", token, Map.of("status", "CLOSED")).getStatusCode(),
                "WAITING -> CLOSED must be a legal transition");
        assertEquals(HttpStatus.CONFLICT,
                put("/api/emergency-visits/" + directCloseId + "/status", token, Map.of("status", "CLOSED")).getStatusCode(),
                "closing an already-CLOSED visit must return the shared 409");

        String unknownId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.NOT_FOUND,
                put("/api/emergency-visits/" + unknownId + "/status", token, Map.of("status", "CLOSED")).getStatusCode(),
                "transitioning an unknown visit must be the shared 404");

        ResponseEntity<Map<String, Object>> unknownTarget =
                put("/api/emergency-visits/" + visitId + "/status", token, Map.of("status", "DERANGED"));
        assertEquals(HttpStatus.CONFLICT, unknownTarget.getStatusCode(),
                "a status outside the transition map must 409, never be stored");
        assertNotNull(unknownTarget.getBody());
        assertTrue(String.valueOf(unknownTarget.getBody().get("message")).contains("no DERANGED transition"),
                "the unknown-target 409 message must be controlled and client-safe");

        List<Map<String, Object>> afterFailures = auditEvents(login(ADMIN_USER));
        assertSingleEvent(afterFailures, "EmergencyVisit", visitId, "CREATE", RECEPTIONIST_USER, "created");
        assertSingleEvent(afterFailures, "EmergencyVisit", visitId, "UPDATE", RECEPTIONIST_USER, "status: IN_TREATMENT");
        assertSingleEvent(afterFailures, "EmergencyVisit", visitId, "UPDATE", RECEPTIONIST_USER, "status: CLOSED");
        assertSingleEvent(afterFailures, "EmergencyVisit", directCloseId, "CREATE", RECEPTIONIST_USER, "created");
        assertSingleEvent(afterFailures, "EmergencyVisit", directCloseId, "UPDATE", RECEPTIONIST_USER, "status: CLOSED");
    }

    /**
     * Delete stays service-owned and audited under the normalized contract:
     * 404-safe on repeat, the shared ApiError body afterwards, and exactly
     * one DELETE audit event with the session actor.
     */
    @Test
    void emergencyVisitDeleteRemainsServiceOwnedSafeAndAudited() {
        String token = login(NURSE_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "delete");
        String visitId = requireId(post("/api/emergency-visits", token, emergencyCreatePayload("delete", patientId)));

        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, token).getStatusCode());
        ResponseEntity<String> gone = getStatus("/api/emergency-visits/" + visitId, token);
        assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode(), "get after delete must be 404");
        Map<String, Object> error = parseError(gone);
        assertEquals(Set.of("timestamp", "status", "error", "message", "path"), error.keySet(),
                "404 must use the shared ApiError contract shape exactly");
        assertEquals(404, ((Number) error.get("status")).intValue(), "ApiError.status must echo 404");
        assertEquals("Not Found", error.get("error"));
        assertEquals("/api/emergency-visits/" + visitId, error.get("path"));
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/emergency-visits/" + visitId, token).getStatusCode(),
                "deleting an unknown visit must be 404, not a silent success");

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "EmergencyVisit", visitId, "DELETE", NURSE_USER, "deleted");
    }

    // ------------------------------------------------------------------
    // Invoice workflow contract (docs/plan2.md Task 4 — normalized)
    // ------------------------------------------------------------------

    /**
     * Task 4 create contract: a verified patient reference, a non-blank
     * invoice number, a validated demo amount stored and echoed in canonical
     * plain-string form (no exponent anywhere), and an [A-Z]{3} demo
     * currency label with NO conversion, FX, or tax meaning. The response is
     * exactly the six-field DTO with the server-owned status DRAFT, and a
     * client-sent status value never reaches storage. Detail and list share
     * the same DTO contract with no persistence metadata anywhere.
     */
    @Test
    void invoiceCreatePinsNormalizedDtoContractWithVerifiedPatientReference() {
        String token = login(BILLING_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "inv-contract");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientId);
        payload.put("invoiceNumber", "INV-NORM-" + suffix);
        payload.put("amount", new BigDecimal("1234.5"));
        payload.put("currency", "USD");
        // Not part of the create contract: the server owns the lifecycle, so
        // this client value must be ignored, never stored.
        payload.put("status", "IMAGINED-RAW");

        ResponseEntity<Map<String, Object>> created = post("/api/invoices", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode(), "a verified patient must allow the invoice create");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(INVOICE_DTO_FIELDS, body.keySet(),
                "the invoice response must be exactly the DTO contract with no persistence metadata");
        assertEquals(patientId, body.get("patientId"),
                "patientId must be the canonical UUID string of the verified patient");
        assertEquals("INV-NORM-" + suffix, body.get("invoiceNumber"));
        assertInstanceOf(String.class, body.get("amount"), "the canonical amount must travel as a plain string");
        assertEquals("1234.5", body.get("amount"), "amount must be the canonical plain string, never exponent form");
        assertEquals("USD", body.get("currency"), "currency is a demo label stored verbatim");
        assertEquals("DRAFT", body.get("status"), "the server must set status=DRAFT, never a client value");
        String invoiceId = requireId(created);

        ResponseEntity<Map<String, Object>> detail = getMap("/api/invoices/" + invoiceId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertNotNull(detail.getBody());
        assertEquals(INVOICE_DTO_FIELDS, detail.getBody().keySet(), "detail must match the same DTO contract");
        assertEquals("DRAFT", detail.getBody().get("status"));

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/invoices", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "invoice list must carry a body");
        List<Map<String, Object>> mine = listBody.stream()
                .filter(i -> invoiceId.equals(String.valueOf(i.get("id"))))
                .collect(Collectors.toList());
        assertEquals(1, mine.size(), "the created invoice must appear exactly once in the list");
        assertEquals(INVOICE_DTO_FIELDS, mine.get(0).keySet(), "every list item must match the DTO contract");

        // An exponent-shaped demo amount must be canonicalized on storage:
        // 1E+3 arrives as a number and must be echoed as the plain "1000".
        Map<String, Object> exponent = new LinkedHashMap<>();
        exponent.put("patientId", patientId);
        exponent.put("invoiceNumber", "INV-EXP-" + suffix);
        exponent.put("amount", new BigDecimal("1E+3"));
        exponent.put("currency", "USD");
        ResponseEntity<Map<String, Object>> exponentResponse = post("/api/invoices", token, exponent);
        assertEquals(HttpStatus.OK, exponentResponse.getStatusCode());
        assertNotNull(exponentResponse.getBody());
        assertEquals("1000", exponentResponse.getBody().get("amount"),
                "the stored amount must use toPlainString() canonical form with no exponent");

        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/invoices/not-a-uuid", token).getStatusCode(),
                "a malformed invoice UUID path must be a client error, never a 500");
    }

    /**
     * Task 4 failure contracts: an unknown patient is the shared safe 404, a
     * non-UUID patientId or a non-numeric amount is a malformed body 400, a
     * negative amount, an amount beyond 12 integer digits or 2 fraction
     * digits, a blank/missing invoice number, and a currency outside
     * [A-Z]{3} are validation 400s — and a duplicate invoice number is the
     * safe shared 409 that overwrites nothing. No rejected write may persist
     * an invoice.
     */
    @Test
    void invoiceCreateRejectsInvalidReferencesAmountsAndDuplicatesWithoutPersisting() {
        String token = login(BILLING_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "inv-bad");
        long invoicesBefore = dashboardCount("invoices", login(ADMIN_USER));

        // Boundary successes first: zero is legal and 12 integer digits with
        // 2 fraction digits is the exact legal maximum (both pinned here so
        // the boundary stays deliberate, not accidental).
        Map<String, Object> zero = invoicePayload("inv-zero", patientId);
        zero.put("amount", BigDecimal.ZERO);
        ResponseEntity<Map<String, Object>> zeroResponse = post("/api/invoices", token, zero);
        assertEquals(HttpStatus.OK, zeroResponse.getStatusCode(), "a zero demo amount is legal");
        assertNotNull(zeroResponse.getBody());
        assertEquals("0", zeroResponse.getBody().get("amount"), "zero must be stored canonically");

        Map<String, Object> max = invoicePayload("inv-max", patientId);
        max.put("amount", new BigDecimal("999999999999.99"));
        ResponseEntity<Map<String, Object>> maxResponse = post("/api/invoices", token, max);
        assertEquals(HttpStatus.OK, maxResponse.getStatusCode(),
                "12 integer digits and 2 fraction digits are legal");
        assertNotNull(maxResponse.getBody());
        assertEquals("999999999999.99", maxResponse.getBody().get("amount"));

        long boundaryCreates = 2;

        // Unknown patient -> shared 404 with the shared ApiError body.
        Map<String, Object> unknownPatient = invoicePayload("inv-unknown", UUID.randomUUID().toString());
        ResponseEntity<Map<String, Object>> notFound = post("/api/invoices", token, unknownPatient);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode(),
                "an unknown patient reference must return the shared 404, never persist");
        Map<String, Object> notFoundBody = notFound.getBody();
        assertNotNull(notFoundBody, "the 404 must carry the shared ApiError body");
        assertEquals(404, ((Number) notFoundBody.get("status")).intValue(), "ApiError.status must echo 404");
        assertEquals("Not Found", notFoundBody.get("error"));

        // Non-UUID patientId -> typed deserialization failure -> 400.
        Map<String, Object> nonUuid = invoicePayload("inv-nonuuid", "not-a-uuid");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, nonUuid).getStatusCode(),
                "a non-UUID patientId fails typed deserialization as a malformed body");

        // Amount validation failures -> validation 400s.
        Map<String, Object> negative = invoicePayload("inv-negative", patientId);
        negative.put("amount", new BigDecimal("-0.01"));
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, negative).getStatusCode(),
                "a negative amount must be rejected");

        Map<String, Object> tooManyIntegers = invoicePayload("inv-wide", patientId);
        tooManyIntegers.put("amount", new BigDecimal("9999999999999.99"));
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, tooManyIntegers).getStatusCode(),
                "an amount with 13 integer digits must be rejected");

        Map<String, Object> tooManyFractions = invoicePayload("inv-precise", patientId);
        tooManyFractions.put("amount", new BigDecimal("1.999"));
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, tooManyFractions).getStatusCode(),
                "an amount with 3 fraction digits must be rejected");

        Map<String, Object> nonNumeric = invoicePayload("inv-nan", patientId);
        nonNumeric.put("amount", "12.345,67-not-a-number");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, nonNumeric).getStatusCode(),
                "a non-numeric amount fails typed deserialization as a malformed body");

        // Invoice number and currency validation failures -> validation 400s.
        Map<String, Object> blankNumber = invoicePayload("inv-blank", patientId);
        blankNumber.put("invoiceNumber", "   ");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, blankNumber).getStatusCode(),
                "a blank invoice number must be rejected");

        Map<String, Object> missingNumber = new LinkedHashMap<>();
        missingNumber.put("patientId", patientId);
        missingNumber.put("amount", new BigDecimal("10.00"));
        missingNumber.put("currency", "USD");
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/invoices", token, missingNumber).getStatusCode(),
                "a missing invoice number must be rejected");

        for (String rejected : List.of("usd", "US", "EURO", "US1", " US")) {
            Map<String, Object> badCurrency = invoicePayload("inv-cur-" + rejected.hashCode(), patientId);
            badCurrency.put("currency", rejected);
            assertEquals(HttpStatus.BAD_REQUEST,
                    post("/api/invoices", token, badCurrency).getStatusCode(),
                    "a currency outside [A-Z]{3} must be rejected: '" + rejected + "'");
        }

        // Duplicate invoice number -> shared 409 with a controlled message,
        // and the original record is untouched (no overwrite).
        String sharedNumber = "INV-DUP-" + suffix;
        Map<String, Object> original = invoicePayload("inv-dup-original", patientId);
        original.put("invoiceNumber", sharedNumber);
        ResponseEntity<Map<String, Object>> first = post("/api/invoices", token, original);
        assertEquals(HttpStatus.OK, first.getStatusCode(), "the first use of the number must succeed");
        String firstId = requireId(first);

        Map<String, Object> duplicate = invoicePayload("inv-dup-second", patientId);
        duplicate.put("invoiceNumber", sharedNumber);
        duplicate.put("amount", new BigDecimal("77.77"));
        ResponseEntity<Map<String, Object>> conflict = post("/api/invoices", token, duplicate);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(),
                "a duplicate invoice number must return the shared 409");
        Map<String, Object> conflictBody = conflict.getBody();
        assertNotNull(conflictBody, "the 409 must carry the shared ApiError body");
        assertEquals(409, ((Number) conflictBody.get("status")).intValue(), "ApiError.status must echo 409");
        assertEquals("Conflict", conflictBody.get("error"));
        String conflictMessage = String.valueOf(conflictBody.get("message"));
        assertTrue(conflictMessage.toLowerCase().contains("already exists"),
                "the 409 message must say the invoice number already exists");
        assertFalse(conflictMessage.contains("Exception"), "the 409 message must not leak exception internals");
        assertFalse(conflictMessage.toUpperCase().contains("SQL"), "the 409 message must not leak SQL internals");

        ResponseEntity<Map<String, Object>> untouched = getMap("/api/invoices/" + firstId, token);
        assertEquals(HttpStatus.OK, untouched.getStatusCode());
        assertNotNull(untouched.getBody());
        assertEquals("10.00", untouched.getBody().get("amount"),
                "the duplicate attempt must not overwrite the original amount");
        assertEquals("DRAFT", untouched.getBody().get("status"),
                "the duplicate attempt must not change the original status");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/invoices", token);
        assertNotNull(list.getBody());
        long twins = list.getBody().stream()
                .filter(i -> sharedNumber.equals(i.get("invoiceNumber")))
                .count();
        assertEquals(1, twins, "both attempts must not leave two invoices sharing the number");

        long invoicesAfter = dashboardCount("invoices", login(ADMIN_USER));
        assertEquals(invoicesBefore + boundaryCreates + 1, invoicesAfter,
                "only the three legal creates may persist; every rejected write persists nothing");
    }

    /**
     * Task 4 transition lifecycle: PUT /api/invoices/{id}/status permits
     * exactly DRAFT -> ISSUED | VOID and ISSUED -> PAID | VOID; PAID and
     * VOID are terminal. The response is the same six-field DTO with the
     * server-applied status and unchanged financial/reference fields.
     * Repeats, backward moves, and unknown targets are the safe shared 409
     * with a controlled message; failed operations produce no audit events,
     * and the successful create and each legal transition produce exactly
     * one audit event with the session actor.
     */
    @Test
    void invoiceTransitionsPinLifecycleConflictsAndAudit() {
        String token = login(BILLING_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "inv-transition");
        String invoiceId = requireId(post("/api/invoices", token, invoicePayload("inv-trn-main", patientId)));

        List<Map<String, Object>> beforeTransitions = auditEvents(login(ADMIN_USER));
        assertSingleEvent(beforeTransitions, "Invoice", invoiceId, "CREATE", BILLING_USER, "created");

        ResponseEntity<Map<String, Object>> issued =
                put("/api/invoices/" + invoiceId + "/status", token, Map.of("status", "ISSUED"));
        assertEquals(HttpStatus.OK, issued.getStatusCode(), "DRAFT -> ISSUED must be a legal transition");
        Map<String, Object> issuedBody = issued.getBody();
        assertNotNull(issuedBody);
        assertEquals(INVOICE_DTO_FIELDS, issuedBody.keySet(), "the transition response must stay on the DTO contract");
        assertEquals("ISSUED", issuedBody.get("status"), "the server must apply the requested legal transition");
        assertEquals(patientId, issuedBody.get("patientId"), "transitions must not change the verified reference");
        assertEquals("INV-" + suffix + "-inv-trn-main", issuedBody.get("invoiceNumber"),
                "transitions must not change the invoice number");
        assertEquals("10.00", issuedBody.get("amount"), "transitions must not change the stored amount");
        assertEquals("USD", issuedBody.get("currency"), "transitions must not change the currency label");

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "Invoice", invoiceId, "UPDATE", BILLING_USER, "status: ISSUED");

        ResponseEntity<Map<String, Object>> paid =
                put("/api/invoices/" + invoiceId + "/status", token, Map.of("status", "PAID"));
        assertEquals(HttpStatus.OK, paid.getStatusCode(), "ISSUED -> PAID must be a legal transition");
        assertNotNull(paid.getBody());
        assertEquals("PAID", paid.getBody().get("status"));

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "Invoice", invoiceId, "UPDATE", BILLING_USER, "status: PAID");

        ResponseEntity<Map<String, Object>> repeat =
                put("/api/invoices/" + invoiceId + "/status", token, Map.of("status", "PAID"));
        assertEquals(HttpStatus.CONFLICT, repeat.getStatusCode(),
                "repeating a transition on a PAID invoice must return the shared 409");
        Map<String, Object> conflict = repeat.getBody();
        assertNotNull(conflict, "the 409 must carry the shared ApiError body");
        assertEquals(409, ((Number) conflict.get("status")).intValue(), "ApiError.status must echo 409");
        assertEquals("Conflict", conflict.get("error"));
        String conflictMessage = String.valueOf(conflict.get("message"));
        assertTrue(conflictMessage.contains("PAID"),
                "the 409 message must name the terminal state, never an entity or stack dump");
        assertFalse(conflictMessage.contains("Exception"), "the 409 message must not leak exception internals");

        assertEquals(HttpStatus.CONFLICT,
                put("/api/invoices/" + invoiceId + "/status", token, Map.of("status", "VOID")).getStatusCode(),
                "a PAID invoice is terminal: every further transition must 409");

        // DRAFT -> VOID is legal directly; VOID is terminal afterwards.
        String voidedId = requireId(post("/api/invoices", token, invoicePayload("inv-trn-void", patientId)));
        assertEquals(HttpStatus.OK,
                put("/api/invoices/" + voidedId + "/status", token, Map.of("status", "VOID")).getStatusCode(),
                "DRAFT -> VOID must be a legal transition");
        assertSingleEvent(auditEvents(login(ADMIN_USER)), "Invoice", voidedId, "UPDATE", BILLING_USER, "status: VOID");
        assertEquals(HttpStatus.CONFLICT,
                put("/api/invoices/" + voidedId + "/status", token, Map.of("status", "ISSUED")).getStatusCode(),
                "a VOID invoice is terminal: every further transition must 409");

        // Repeated and unknown targets on a live DRAFT are conflicts, never
        // stored; then the legal issue happens and backward moves conflict.
        String draftedId = requireId(post("/api/invoices", token, invoicePayload("inv-trn-back", patientId)));
        assertEquals(HttpStatus.CONFLICT,
                put("/api/invoices/" + draftedId + "/status", token, Map.of("status", "DRAFT")).getStatusCode(),
                "DRAFT -> DRAFT is not in the transition map and must 409");
        ResponseEntity<Map<String, Object>> unknownTarget =
                put("/api/invoices/" + draftedId + "/status", token, Map.of("status", "RUINED"));
        assertEquals(HttpStatus.CONFLICT, unknownTarget.getStatusCode(),
                "a status outside the transition map must 409, never be stored");
        assertNotNull(unknownTarget.getBody());
        assertTrue(String.valueOf(unknownTarget.getBody().get("message")).contains("no RUINED transition"),
                "the unknown-target 409 message must be controlled and client-safe");
        assertEquals(HttpStatus.OK,
                put("/api/invoices/" + draftedId + "/status", token, Map.of("status", "ISSUED")).getStatusCode(),
                "DRAFT -> ISSUED must stay legal for the backward-move probe");
        assertEquals(HttpStatus.CONFLICT,
                put("/api/invoices/" + draftedId + "/status", token, Map.of("status", "DRAFT")).getStatusCode(),
                "ISSUED -> DRAFT is a backward move and must 409");

        String unknownId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.NOT_FOUND,
                put("/api/invoices/" + unknownId + "/status", token, Map.of("status", "ISSUED")).getStatusCode(),
                "transitioning an unknown invoice must be the shared 404");

        // Exactly one audit event per successful mutation and none for any
        // rejected write above.
        List<Map<String, Object>> after = auditEvents(login(ADMIN_USER));
        assertSingleEvent(after, "Invoice", invoiceId, "CREATE", BILLING_USER, "created");
        assertSingleEvent(after, "Invoice", invoiceId, "UPDATE", BILLING_USER, "status: ISSUED");
        assertSingleEvent(after, "Invoice", invoiceId, "UPDATE", BILLING_USER, "status: PAID");
        assertSingleEvent(after, "Invoice", voidedId, "CREATE", BILLING_USER, "created");
        assertSingleEvent(after, "Invoice", voidedId, "UPDATE", BILLING_USER, "status: VOID");
        assertSingleEvent(after, "Invoice", draftedId, "CREATE", BILLING_USER, "created");
        assertSingleEvent(after, "Invoice", draftedId, "UPDATE", BILLING_USER, "status: ISSUED");
        long updatesForTerminalProbe = after.stream()
                .filter(e -> "Invoice".equals(e.get("resourceType")) && voidedId.equals(e.get("resourceId"))
                        && "UPDATE".equals(e.get("action")))
                .count();
        assertEquals(1, updatesForTerminalProbe,
                "the terminal and unknown-target rejections must not add audit events");
        long updatesForBackwardProbe = after.stream()
                .filter(e -> "Invoice".equals(e.get("resourceType")) && draftedId.equals(e.get("resourceId"))
                        && "UPDATE".equals(e.get("action")))
                .count();
        assertEquals(1, updatesForBackwardProbe,
                "the repeated and backward-move rejections must not add audit events");
    }

    /**
     * Delete stays service-owned and audited under the normalized contract:
     * 404-safe on repeat, the shared ApiError body afterwards, and exactly
     * one DELETE audit event with the session actor.
     */
    @Test
    void invoiceDeleteRemainsServiceOwnedSafeAndAudited() {
        String token = login(BILLING_USER);
        String patientId = createSyntheticPatient(login(ADMIN_USER), "inv-delete");
        String invoiceId = requireId(post("/api/invoices", token, invoicePayload("inv-delete", patientId)));

        assertEquals(HttpStatus.OK, delete("/api/invoices/" + invoiceId, token).getStatusCode());
        ResponseEntity<String> gone = getStatus("/api/invoices/" + invoiceId, token);
        assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode(), "get after delete must be 404");
        Map<String, Object> error = parseError(gone);
        assertEquals(Set.of("timestamp", "status", "error", "message", "path"), error.keySet(),
                "404 must use the shared ApiError contract shape exactly");
        assertEquals(404, ((Number) error.get("status")).intValue(), "ApiError.status must echo 404");
        assertEquals("Not Found", error.get("error"));
        assertEquals("/api/invoices/" + invoiceId, error.get("path"));
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/invoices/" + invoiceId, token).getStatusCode(),
                "deleting an unknown invoice must be 404, not a silent success");

        assertSingleEvent(auditEvents(login(ADMIN_USER)), "Invoice", invoiceId, "DELETE", BILLING_USER, "deleted");
    }

    // ------------------------------------------------------------------
    // Bed CRUD contract
    // ------------------------------------------------------------------

    /**
     * Task 6 replaces raw Bed persistence exposure with a branch-owned DTO.
     * Extra client fields are ignored at the HTTP boundary and cannot select
     * ownership, occupancy, or a patient reference.
     */
    @Test
    void bedCreateDerivesOwnershipAndReturnsOnlyNormalizedDtoFields() {
        String token = login(RECEPTIONIST_USER);
        String forgedBranchId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of(
                "ward", "ward-normalized-" + suffix,
                "room", "room-01",
                "bedNumber", "BED-01",
                "branchId", forgedBranchId,
                "occupancyStatus", "OCCUPIED",
                "patientId", "not-even-a-uuid");

        ResponseEntity<Map<String, Object>> created = post("/api/beds", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(BED_DTO_FIELDS, body.keySet());
        assertEquals("AVAILABLE", body.get("occupancyStatus"));
        assertNotNull(body.get("branchId"));
        assertNotEquals(forgedBranchId, body.get("branchId").toString());
        String bedId = requireId(created);

        ResponseEntity<Map<String, Object>> fetched = getMap("/api/beds/" + bedId, token);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertNotNull(fetched.getBody());
        assertEquals(body, fetched.getBody());
        assertEquals(HttpStatus.OK, delete("/api/beds/" + bedId, token).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/beds/" + bedId, token).getStatusCode());
    }

    // ------------------------------------------------------------------
    // Dashboard baseline
    // ------------------------------------------------------------------

    /**
     * Dashboard contract at integration level: exactly the eleven Task 5 keys
     * — five whole-table totals plus status-aware aggregates — with beds
     * adding no key and deletes feeding straight back into the totals.
     * Detailed bucket semantics are pinned in DashboardApiTest; this test
     * preserves its existing deltas over test-created synthetic records only.
     * The Task 2 admission contract needs one verified patient, created
     * before the baseline snapshot.
     */
    @Test
    void dashboardSummaryPinsExactKeySetAndRowCountSemantics() {
        String adminToken = login(ADMIN_USER);
        String patientId = createSyntheticPatient(adminToken, "dash");

        ResponseEntity<Map<String, Object>> before = getMap("/api/dashboard", adminToken);
        assertEquals(HttpStatus.OK, before.getStatusCode());
        Map<String, Object> beforeBody = before.getBody();
        assertNotNull(beforeBody, "dashboard response must carry a body");
        assertEquals(DASHBOARD_KEYS, beforeBody.keySet(), "the dashboard must expose exactly the eleven Task 5 keys");
        for (String key : DASHBOARD_KEYS) {
            assertInstanceOf(Number.class, beforeBody.get(key), "dashboard key '" + key + "' must be a numeric count");
        }
        long patientsBefore = count(beforeBody, "patients");
        long appointmentsBefore = count(beforeBody, "appointments");
        long admissionsBefore = count(beforeBody, "admissions");
        long emergencyBefore = count(beforeBody, "emergencyVisits");
        long invoicesBefore = count(beforeBody, "invoices");

        String admissionId = requireId(post("/api/admissions", adminToken, admissionCreatePayload("dash", patientId)));
        String visitId = requireId(post("/api/emergency-visits", adminToken, emergencyCreatePayload("dash", patientId)));
        String invoiceId = requireId(post("/api/invoices", adminToken, invoicePayload("dash", patientId)));
        String bedId = requireId(post("/api/beds", adminToken, bedPayload()));

        ResponseEntity<Map<String, Object>> after = getMap("/api/dashboard", adminToken);
        assertEquals(HttpStatus.OK, after.getStatusCode());
        Map<String, Object> afterBody = after.getBody();
        assertNotNull(afterBody);
        assertEquals(DASHBOARD_KEYS, afterBody.keySet(),
                "creating a bed must not introduce a new dashboard key");
        assertEquals(admissionsBefore + 1, count(afterBody, "admissions"), "each created admission must count once");
        assertEquals(emergencyBefore + 1, count(afterBody, "emergencyVisits"), "each created visit must count once");
        assertEquals(invoicesBefore + 1, count(afterBody, "invoices"), "each created invoice must count once");
        assertEquals(patientsBefore, count(afterBody, "patients"), "no patient was created after the baseline snapshot");
        assertEquals(appointmentsBefore, count(afterBody, "appointments"), "no appointment was created");

        assertEquals(HttpStatus.OK, delete("/api/admissions/" + admissionId, adminToken).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, adminToken).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/invoices/" + invoiceId, adminToken).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/beds/" + bedId, adminToken).getStatusCode());

        ResponseEntity<Map<String, Object>> restored = getMap("/api/dashboard", adminToken);
        assertEquals(HttpStatus.OK, restored.getStatusCode());
        Map<String, Object> restoredBody = restored.getBody();
        assertNotNull(restoredBody);
        assertEquals(admissionsBefore, count(restoredBody, "admissions"),
                "deletes must feed straight back into the raw row counts");
        assertEquals(emergencyBefore, count(restoredBody, "emergencyVisits"));
        assertEquals(invoicesBefore, count(restoredBody, "invoices"));
    }

    // ------------------------------------------------------------------
    // Audit baseline (existing seam: ADMIN-only GET /api/audit)
    // ------------------------------------------------------------------

    /**
     * Audit baseline: successful CREATE and DELETE are recorded, one event
     * per mutation, with the session actor and the literal details strings.
     * Task 2 holds admissions to the same bar plus one UPDATE event per
     * discharge (pinned in the discharge lifecycle test); Task 7 now holds
     * every care-operations family to this bar with canonical transition
     * details (the dedicated audit sweep below).
     */
    @Test
    void createAndDeleteCurrentlyProduceOneAuditEventEachWithSessionActor() {
        String adminToken = login(ADMIN_USER);
        String patientId = createSyntheticPatient(adminToken, "audit");
        String admissionId = requireId(post("/api/admissions", login(NURSE_USER), admissionCreatePayload("audit", patientId)));
        String visitId = requireId(post("/api/emergency-visits", login(DOCTOR_USER), emergencyCreatePayload("audit", patientId)));
        String invoiceId = requireId(post("/api/invoices", login(BILLING_USER), invoicePayload("audit", patientId)));
        String bedId = requireId(post("/api/beds", login(ADMIN_USER), bedPayload()));

        List<Map<String, Object>> events = auditEvents(adminToken);
        assertSingleEvent(events, "Admission", admissionId, "CREATE", NURSE_USER, "created");
        assertSingleEvent(events, "EmergencyVisit", visitId, "CREATE", DOCTOR_USER, "created");
        assertSingleEvent(events, "Invoice", invoiceId, "CREATE", BILLING_USER, "created");
        assertSingleEvent(events, "Bed", bedId, "CREATE", ADMIN_USER, "created");

        assertEquals(HttpStatus.OK, delete("/api/admissions/" + admissionId, login(NURSE_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, login(DOCTOR_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/invoices/" + invoiceId, login(BILLING_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/beds/" + bedId, login(ADMIN_USER)).getStatusCode());

        List<Map<String, Object>> afterDeletion = auditEvents(adminToken);
        assertSingleEvent(afterDeletion, "Admission", admissionId, "DELETE", NURSE_USER, "deleted");
        assertSingleEvent(afterDeletion, "EmergencyVisit", visitId, "DELETE", DOCTOR_USER, "deleted");
        assertSingleEvent(afterDeletion, "Invoice", invoiceId, "DELETE", BILLING_USER, "deleted");
        assertSingleEvent(afterDeletion, "Bed", bedId, "DELETE", ADMIN_USER, "deleted");
    }

    /**
     * Task 7 audit sweep (docs/plan2.md Task 7) over one synthetic
     * care-operations workflow: every successful mutation produces exactly
     * one audit event whose actor is the authenticated session user (never a
     * payload value) and whose transition details name the resulting status
     * canonically ("status: <CANONICAL_STATUS>"; CREATE keeps "created");
     * every failed operation (404 unknown reference/record, 400
     * blank/malformed body, 409 duplicate invoice number and
     * illegal/repeated/terminal transition) adds no event and leaves the
     * persisted state untouched; every event keeps the stable public shape
     * and carries only whitelisted fields — no tokens, credentials, request
     * payloads, patient names, reasons, complaints, or financial values.
     */
    @Test
    void careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage() {
        String adminToken = login(ADMIN_USER);
        String patientId = createSyntheticPatient(adminToken, "audit-sweep");

        // Actor provenance: the create runs under the nurse session while the
        // payload carries a forged actor value, and the discharge runs under
        // a different session — the audit actor must track the login only.
        String nurseToken = login(NURSE_USER);
        Map<String, Object> admissionPayload = admissionCreatePayload("sweep", patientId);
        admissionPayload.put("actor", "payload-forged-actor");
        String admissionId = requireId(post("/api/admissions", nurseToken, admissionPayload));
        String doctorToken = login(DOCTOR_USER);
        assertEquals(HttpStatus.OK, put("/api/admissions/" + admissionId + "/status", doctorToken,
                Map.of("status", "DISCHARGED")).getStatusCode(), "the legal discharge must succeed");

        // Emergency visit: WAITING -> IN_TREATMENT -> CLOSED.
        String visitId = requireId(post("/api/emergency-visits", doctorToken,
                emergencyCreatePayload("sweep", patientId)));
        assertEquals(HttpStatus.OK, put("/api/emergency-visits/" + visitId + "/status", doctorToken,
                Map.of("status", "IN_TREATMENT")).getStatusCode());
        assertEquals(HttpStatus.OK, put("/api/emergency-visits/" + visitId + "/status", doctorToken,
                Map.of("status", "CLOSED")).getStatusCode());

        // Invoice: create then DRAFT -> ISSUED -> PAID.
        String billingToken = login(BILLING_USER);
        Map<String, Object> sweepInvoice = invoicePayload("sweep", patientId);
        sweepInvoice.put("amount", new BigDecimal("77.77"));
        ResponseEntity<Map<String, Object>> invoiceCreated = post("/api/invoices", billingToken, sweepInvoice);
        String invoiceId = requireId(invoiceCreated);
        String invoiceNumber = String.valueOf(invoiceCreated.getBody().get("invoiceNumber"));
        assertEquals(HttpStatus.OK, put("/api/invoices/" + invoiceId + "/status", billingToken,
                Map.of("status", "ISSUED")).getStatusCode());
        assertEquals(HttpStatus.OK, put("/api/invoices/" + invoiceId + "/status", billingToken,
                Map.of("status", "PAID")).getStatusCode());

        List<Map<String, Object>> events = auditEvents(adminToken);
        assertSingleEvent(events, "Admission", admissionId, "CREATE", NURSE_USER, "created");
        assertSingleEvent(events, "Admission", admissionId, "UPDATE", DOCTOR_USER, "status: DISCHARGED");
        assertSingleEvent(events, "EmergencyVisit", visitId, "CREATE", DOCTOR_USER, "created");
        assertSingleEvent(events, "EmergencyVisit", visitId, "UPDATE", DOCTOR_USER, "status: IN_TREATMENT");
        assertSingleEvent(events, "EmergencyVisit", visitId, "UPDATE", DOCTOR_USER, "status: CLOSED");
        assertSingleEvent(events, "Invoice", invoiceId, "CREATE", BILLING_USER, "created");
        assertSingleEvent(events, "Invoice", invoiceId, "UPDATE", BILLING_USER, "status: ISSUED");
        assertSingleEvent(events, "Invoice", invoiceId, "UPDATE", BILLING_USER, "status: PAID");

        // Failure sweep: every rejection below must add no audit event.
        long admissionUpdatesBefore = countAuditEvents(events, "Admission", admissionId, "UPDATE");
        long visitUpdatesBefore = countAuditEvents(events, "EmergencyVisit", visitId, "UPDATE");
        long invoiceUpdatesBefore = countAuditEvents(events, "Invoice", invoiceId, "UPDATE");

        assertEquals(HttpStatus.CONFLICT, put("/api/admissions/" + admissionId + "/status", doctorToken,
                Map.of("status", "DISCHARGED")).getStatusCode(), "a repeated discharge must 409");
        assertEquals(HttpStatus.BAD_REQUEST, put("/api/admissions/" + admissionId + "/status", doctorToken,
                Map.of("status", "   ")).getStatusCode(), "a blank status must fail validation with 400");
        assertEquals(HttpStatus.NOT_FOUND, put("/api/admissions/" + UUID.randomUUID() + "/status", doctorToken,
                Map.of("status", "DISCHARGED")).getStatusCode(), "an unknown admission must 404");
        assertEquals(HttpStatus.NOT_FOUND, post("/api/admissions", doctorToken,
                admissionCreatePayload("sweep-404", UUID.randomUUID().toString())).getStatusCode(),
                "an unknown patient reference must 404");

        assertEquals(HttpStatus.CONFLICT, put("/api/emergency-visits/" + visitId + "/status", doctorToken,
                Map.of("status", "CLOSED")).getStatusCode(), "a transition on a CLOSED visit must 409");
        assertEquals(HttpStatus.CONFLICT, put("/api/emergency-visits/" + visitId + "/status", doctorToken,
                Map.of("status", "DERANGED")).getStatusCode(), "an unknown target must 409");
        assertEquals(HttpStatus.BAD_REQUEST, put("/api/emergency-visits/" + visitId + "/status", doctorToken,
                Map.of("status", "   ")).getStatusCode(), "a blank emergency status must fail validation with 400");
        assertEquals(HttpStatus.NOT_FOUND, put("/api/emergency-visits/" + UUID.randomUUID() + "/status", doctorToken,
                Map.of("status", "CLOSED")).getStatusCode(), "an unknown visit must 404");

        Map<String, Object> duplicate = invoicePayload("sweep-duplicate", patientId);
        duplicate.put("invoiceNumber", invoiceNumber);
        assertEquals(HttpStatus.CONFLICT, post("/api/invoices", billingToken, duplicate).getStatusCode(),
                "a duplicate invoice number must 409");
        assertEquals(HttpStatus.CONFLICT, put("/api/invoices/" + invoiceId + "/status", billingToken,
                Map.of("status", "PAID")).getStatusCode(), "a transition on a PAID invoice must 409");
        assertEquals(HttpStatus.BAD_REQUEST, put("/api/invoices/" + invoiceId + "/status", billingToken,
                Map.of("status", "   ")).getStatusCode(), "a blank invoice status must fail validation with 400");
        assertEquals(HttpStatus.NOT_FOUND, put("/api/invoices/" + UUID.randomUUID() + "/status", billingToken,
                Map.of("status", "ISSUED")).getStatusCode(), "an unknown invoice must 404");

        List<Map<String, Object>> afterFailures = auditEvents(adminToken);
        assertEquals(admissionUpdatesBefore, countAuditEvents(afterFailures, "Admission", admissionId, "UPDATE"),
                "failed admission operations must not add audit events");
        assertEquals(visitUpdatesBefore, countAuditEvents(afterFailures, "EmergencyVisit", visitId, "UPDATE"),
                "failed emergency operations must not add audit events");
        assertEquals(invoiceUpdatesBefore, countAuditEvents(afterFailures, "Invoice", invoiceId, "UPDATE"),
                "failed invoice operations must not add audit events");

        // Persisted state is untouched by every failed operation above.
        assertEquals("DISCHARGED", getMap("/api/admissions/" + admissionId, adminToken).getBody().get("status"),
                "the repeated/blank/unknown discharge attempts must not change the admission");
        assertEquals("CLOSED", getMap("/api/emergency-visits/" + visitId, adminToken).getBody().get("status"),
                "the repeated/blank/unknown transition attempts must not change the visit");
        Map<String, Object> untouchedInvoice = getMap("/api/invoices/" + invoiceId, adminToken).getBody();
        assertNotNull(untouchedInvoice);
        assertEquals("PAID", untouchedInvoice.get("status"),
                "the duplicate and failed transitions must not change the invoice status");
        assertEquals("77.77", untouchedInvoice.get("amount"),
                "the duplicate attempt must not overwrite the original amount");

        // Data minimization over every event the sweep owns: canonical
        // details whitelist, session-actor whitelist, stable shape, and a
        // sensitive-content scan of the serialized events.
        List<Map<String, Object>> swept = afterFailures.stream()
                .filter(e -> List.of(admissionId, visitId, invoiceId).contains(String.valueOf(e.get("resourceId"))))
                .collect(Collectors.toList());
        assertEquals(8, swept.size(), "the swept workflow must own exactly eight audit events");
        Map<String, Set<String>> allowedDetails = Map.of(
                admissionId, Set.of("created", "status: DISCHARGED"),
                visitId, Set.of("created", "status: IN_TREATMENT", "status: CLOSED"),
                invoiceId, Set.of("created", "status: ISSUED", "status: PAID"));
        Set<String> allowedActors = Set.of(ADMIN_USER, NURSE_USER, DOCTOR_USER, BILLING_USER);
        for (Map<String, Object> event : swept) {
            String resourceId = String.valueOf(event.get("resourceId"));
            assertTrue(allowedDetails.get(resourceId).contains(event.get("details")),
                    "audit details must stay the canonical mutation label, never a payload: " + event.get("details"));
            assertTrue(allowedActors.contains(event.get("actor")),
                    "the audit actor must be an authenticated session user: " + event.get("actor"));
            assertDoesNotThrow(() -> Instant.parse(String.valueOf(event.get("occurredAt"))),
                    "occurredAt must be a real instant");
        }

        String dumped;
        try {
            dumped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(swept);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("audit events must serialize for the sensitive-content scan", e);
        }
        for (String forbidden : List.of(
                "Synthetic Patient " + suffix + " audit-sweep",
                "synthetic admission " + suffix + " sweep",
                "synthetic complaint " + suffix + " sweep",
                invoiceNumber, "77.77", "USD",
                TEST_ACCOUNT_PASSWORD,
                "Bearer ", "Authorization", "Exception", " at ", "SELECT ")) {
            assertFalse(dumped.contains(forbidden),
                    "audit events must never carry sensitive content: " + forbidden);
        }
    }

    /**
     * Pins exactly one audit event per (resourceType, resourceId, action,
     * details): the details filter matters for resources that legitimately
     * accumulate several UPDATE events — an emergency visit carries one
     * UPDATE per transition ("status: IN_TREATMENT", "status: CLOSED") —
     * while still proving no duplicate or unexpected event exists for the
     * named mutation. Every pinned event must also keep the stable Task 7
     * public shape (six contract fields and no expansion).
     */
    private void assertSingleEvent(List<Map<String, Object>> events, String resourceType, String resourceId,
                                   String action, String actor, String details) {
        List<Map<String, Object>> matches = events.stream()
                .filter(e -> resourceType.equals(e.get("resourceType")) && resourceId.equals(e.get("resourceId"))
                        && action.equals(e.get("action")) && details.equals(e.get("details")))
                .collect(Collectors.toList());
        assertEquals(1, matches.size(),
                "exactly one " + action + " ('" + details + "') audit event must exist for "
                        + resourceType + " " + resourceId);
        Map<String, Object> event = matches.get(0);
        assertEventShape(event);
        assertEquals(action, event.get("action"));
        assertEquals(actor, event.get("actor"), "the audit actor must be the authenticated session user");
        assertEquals(details, event.get("details"), "the audit details must name the performed mutation");
        assertNotNull(event.get("occurredAt"), "audit events must carry occurredAt");
    }

    /**
     * Task 7 shape pin: the public event shape stays exactly the six
     * contract fields; persistence metadata may exist on the entity JSON
     * (BaseEntity), but nothing beyond it may appear — the public contract
     * must not expand.
     */
    private void assertEventShape(Map<String, Object> event) {
        Set<String> keys = event.keySet();
        assertTrue(keys.containsAll(AUDIT_CONTRACT_KEYS),
                "every audit event must expose the six contract fields, saw: " + keys);
        assertTrue(union(AUDIT_CONTRACT_KEYS, AUDIT_METADATA_KEYS).containsAll(keys),
                "the audit payload contract must not expand beyond the six contract fields "
                        + "and tolerated persistence metadata, saw: " + keys);
    }

    /** Counts one resource's events for an (resourceType, resourceId, action) triple, ignoring details. */
    private long countAuditEvents(List<Map<String, Object>> events, String resourceType, String resourceId,
                                  String action) {
        return events.stream()
                .filter(e -> resourceType.equals(e.get("resourceType")) && resourceId.equals(e.get("resourceId"))
                        && action.equals(e.get("action")))
                .count();
    }

    private List<Map<String, Object>> auditEvents(String adminToken) {
        ResponseEntity<List<Map<String, Object>>> audit = getList("/api/audit", adminToken);
        assertEquals(HttpStatus.OK, audit.getStatusCode(), "only the ADMIN audit route is the audit seam today");
        List<Map<String, Object>> body = audit.getBody();
        assertNotNull(body, "audit response must carry a body");
        return body;
    }

    // ------------------------------------------------------------------
    // Synthetic payload builders (fabricated values only)
    // ------------------------------------------------------------------

    /**
     * Task 2 create contract body: verified patientId, typed ISO admittedAt,
     * non-blank reason — nothing else. The optional patientId override lets
     * failure tests substitute unknown or malformed references.
     */
    private Map<String, Object> admissionCreatePayload(String tag, String patientIdOverride) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientIdOverride != null ? patientIdOverride : UUID.randomUUID().toString());
        payload.put("admittedAt", "2031-01-01T08:15:30");
        payload.put("reason", "synthetic admission " + suffix + " " + tag);
        return payload;
    }

    /** Registers one synthetic patient through the Task 3 Phase 1 contract and returns its id. */
    private String createSyntheticPatient(String token, String tag) {
        Map<String, Object> patient = Map.of(
                "medicalRecordNumber", "MRN-CAREOPS-" + suffix + "-" + tag,
                "fullName", "Synthetic Patient " + suffix + " " + tag);
        ResponseEntity<Map<String, Object>> created = post("/api/patients", token, patient);
        assertEquals(HttpStatus.OK, created.getStatusCode(), "the synthetic patient fixture must register cleanly");
        return requireId(created);
    }

    /**
     * Task 3 create contract body: verified patientId, typed ISO arrivalAt, a
     * neutral 1–5 demo triage label (no clinical meaning), and a non-blank
     * synthetic complaint — nothing else. The optional patientId override
     * lets failure tests substitute unknown or malformed references.
     */
    private Map<String, Object> emergencyCreatePayload(String tag, String patientIdOverride) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientIdOverride != null ? patientIdOverride : UUID.randomUUID().toString());
        payload.put("arrivalAt", "2031-01-01T09:15:00");
        payload.put("triageLevel", "3");
        payload.put("chiefComplaint", "synthetic complaint " + suffix + " " + tag);
        return payload;
    }

    /**
     * Task 4 create contract body: a verified patientId, a non-blank
     * invoice number, a validated demo amount, and an [A-Z]{3} demo currency
     * label — plus a client status value the server must ignore. The
     * optional patientId override lets failure tests substitute unknown or
     * malformed references.
     */
    private Map<String, Object> invoicePayload(String tag, String patientIdOverride) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("patientId", patientIdOverride != null ? patientIdOverride : UUID.randomUUID().toString());
        payload.put("invoiceNumber", "INV-" + suffix + "-" + tag);
        payload.put("amount", new BigDecimal("10.00"));
        payload.put("currency", "USD");
        payload.put("status", "DRAFT-RAW");
        return payload;
    }

    private Map<String, Object> invoicePayload(String tag) {
        return invoicePayload(tag, null);
    }

    private Map<String, Object> bedPayload() {
        return Map.of(
                "ward", "ward-" + suffix,
                "room", "room-" + suffix,
                "bedNumber", "BED-" + suffix,
                "occupancyStatus", "FREE-RAW",
                "patientId", UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------
    // Helpers (established PatientJourneyApiTest patterns)
    // ------------------------------------------------------------------

    private long dashboardCount(String key, String token) {
        ResponseEntity<Map<String, Object>> dashboard = getMap("/api/dashboard", token);
        assertEquals(HttpStatus.OK, dashboard.getStatusCode());
        Map<String, Object> body = dashboard.getBody();
        assertNotNull(body, "dashboard response must carry a body");
        return count(body, key);
    }

    private long count(Map<String, Object> body, String key) {
        Object value = body.get(key);
        assertInstanceOf(Number.class, value, "dashboard key '" + key + "' must be a numeric count");
        return ((Number) value).longValue();
    }

    private Set<String> union(Set<String> left, Set<String> right) {
        Set<String> both = new HashSet<>(left);
        both.addAll(right);
        return both;
    }

    private String requireId(ResponseEntity<Map<String, Object>> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "entity responses must carry a body");
        Object id = body.get("id");
        assertNotNull(id, "entity responses must carry their UUID identity");
        return String.valueOf(id);
    }

    private Map<String, Object> parseError(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new AssertionError("error response must carry a JSON body", e);
        }
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

    private ResponseEntity<Map<String, Object>> delete(String path, String token) {
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers(token)),
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

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}

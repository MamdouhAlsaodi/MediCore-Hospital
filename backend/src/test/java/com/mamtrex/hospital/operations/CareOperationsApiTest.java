package com.mamtrex.hospital.operations;

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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Care-operations characterization baseline (docs/plan2.md Task 1, packet
 * MEDICORE-PLAN2-TASK1-030). This test deliberately freezes TODAY'S raw
 * behavior of /api/admissions, /api/emergency-visits, /api/invoices,
 * /api/beds, and /api/dashboard so later Plan 2 vertical slices change it
 * visibly. It is not a statement of desired behavior: the weaknesses it
 * records are intentional later change targets —
 *
 * - controllers return JPA entities directly, so responses expose the
 *   persistence metadata id/createdAt/updatedAt/version (later: DTOs);
 * - every request field is a raw @NotBlank String stored verbatim — raw
 *   statuses, non-ISO dates, arbitrary amounts, and non-UUID or nonexistent
 *   patientId values are all accepted (later: verified references, typed
 *   values, per-domain status sets);
 * - invoiceNumber has no uniqueness and there is no invoice lifecycle;
 * - admissions/emergency visits have no update or transition endpoint
 *   (PUT returns 405 today); create/get/list/delete only;
 * - the dashboard exposes exactly five raw count keys with row-count
 *   semantics and no status awareness (beds are not counted);
 * - the RBAC family rules allow DOCTOR and NURSE to WRITE admissions and
 *   emergency visits today (plan2 §7.1 write-role narrowing decision), and
 *   BILLING is isolated to invoices with 403 elsewhere.
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

    /** Current entity-shaped response contract: persistence metadata leaks. */
    private static final Set<String> METADATA_KEYS = Set.of("id", "createdAt", "updatedAt", "version");

    private static final Set<String> ADMISSION_ENTITY_FIELDS = Set.of(
            "patientId", "admittedAt", "dischargedAt", "reason", "status");

    private static final Set<String> EMERGENCY_ENTITY_FIELDS = Set.of(
            "patientId", "arrivalAt", "triageLevel", "chiefComplaint", "status");

    private static final Set<String> INVOICE_ENTITY_FIELDS = Set.of(
            "patientId", "invoiceNumber", "amount", "currency", "status");

    private static final Set<String> BED_ENTITY_FIELDS = Set.of(
            "ward", "room", "bedNumber", "occupancyStatus", "patientId");

    /** Current dashboard contract: exactly these five raw count keys. */
    private static final Set<String> DASHBOARD_KEYS = Set.of(
            "patients", "appointments", "admissions", "emergencyVisits", "invoices");

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
        record(account(ADMIN_USER, Role.ADMIN));
        record(account(BILLING_USER, Role.BILLING));
        record(account(DOCTOR_USER, Role.DOCTOR));
        record(account(NURSE_USER, Role.NURSE));
        record(account(RECEPTIONIST_USER, Role.RECEPTIONIST));
    }

    private UserAccount account(String username, Role role) {
        return new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(role));
    }

    private void record(UserAccount account) {
        if (accounts.findByUsername(account.getUsername()).isEmpty()) {
            accounts.save(account);
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
     * included). The family rules currently carry NO method distinction, so
     * NURSE holds a live admission write today (plan2 §7.1 decision pending).
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
     * method-level narrowing, so NURSE can create (and delete) admissions
     * directly. Pinned because plan2 §7.1 leaves narrowing to an owner
     * decision; if that decision lands, this test is the visible tripwire.
     */
    @Test
    void nurseCurrentlyHoldsAdmissionAndEmergencyWritesUnderFamilyRule() {
        String nurseToken = login(NURSE_USER);
        ResponseEntity<Map<String, Object>> admitted = post("/api/admissions", nurseToken, admissionPayload("nurse"));
        assertEquals(HttpStatus.OK, admitted.getStatusCode(),
                "NURSE must currently be able to write admissions (no method narrowing exists)");
        String admissionId = requireId(admitted);

        ResponseEntity<Map<String, Object>> visited = post("/api/emergency-visits", nurseToken, emergencyPayload("nurse"));
        assertEquals(HttpStatus.OK, visited.getStatusCode(),
                "NURSE must currently be able to write emergency visits (no method narrowing exists)");
        String visitId = requireId(visited);

        assertEquals(HttpStatus.OK, delete("/api/admissions/" + admissionId, nurseToken).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, nurseToken).getStatusCode());
    }

    // ------------------------------------------------------------------
    // Admission CRUD contract (raw entity responses today)
    // ------------------------------------------------------------------

    /**
     * Admissions today: create/get/list/delete only, raw entity responses
     * carrying persistence metadata, verbatim raw-string storage, malformed
     * UUID paths as client errors, no update endpoint (405), delete as the
     * only mutation, and 404 with the shared ApiError body afterwards.
     */
    @Test
    void admissionCrudExposesRawEntityContractWithPersistenceMetadata() {
        String token = login(ADMIN_USER);
        String unknownPatientId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of(
                "patientId", unknownPatientId,
                "admittedAt", "not-a-timestamp-" + suffix,
                "dischargedAt", "also-not-a-timestamp",
                "reason", "raw unvalidated admission reason " + suffix,
                "status", "WHENEVER-RAW");
        Set<String> entityContract = union(METADATA_KEYS, ADMISSION_ENTITY_FIELDS);

        ResponseEntity<Map<String, Object>> created = post("/api/admissions", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode(),
                "arbitrary raw string values and an unknown patientId must currently be accepted");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(entityContract, body.keySet(),
                "the admission response must currently be the raw entity incl. persistence metadata");
        assertEquals(unknownPatientId, body.get("patientId"),
                "patientId must currently be stored verbatim without any reference verification");
        assertEquals("not-a-timestamp-" + suffix, body.get("admittedAt"), "raw strings must be stored verbatim");
        assertEquals("WHENEVER-RAW", body.get("status"), "status must currently be an unconstrained raw string");
        assertNotNull(body.get("createdAt"), "the entity response must currently expose createdAt");
        assertNotNull(body.get("updatedAt"), "the entity response must currently expose updatedAt");
        assertInstanceOf(Number.class, body.get("version"), "the entity response must currently expose version");
        String admissionId = requireId(created);

        ResponseEntity<Map<String, Object>> detail = getMap("/api/admissions/" + admissionId, token);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertNotNull(detail.getBody());
        assertEquals(entityContract, detail.getBody().keySet(), "detail must match the same raw entity contract");
        assertEquals(unknownPatientId, detail.getBody().get("patientId"));

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/admissions", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "admission list must carry a body");
        assertTrue(listBody.stream().anyMatch(a -> admissionId.equals(String.valueOf(a.get("id")))),
                "the created admission must appear in the raw list");
        for (Map<String, Object> item : listBody) {
            assertEquals(entityContract, item.keySet(), "every list item must match the raw entity contract");
        }

        assertEquals(HttpStatus.BAD_REQUEST, getStatus("/api/admissions/not-a-uuid", token).getStatusCode(),
                "a malformed admission UUID path must be a client error, never a 500");

        // No admission update or discharge transition exists today. MVC resolves the
        // unsupported PUT as HttpRequestMethodNotSupportedException, but Boot's error
        // dispatch to /error re-enters the stateless JWT chain without the skipped
        // JwtFilter, so /error (anyRequest().authenticated()) answers the anonymous
        // re-dispatch with the 401 entry point — the observed client-visible outcome.
        assertEquals(HttpStatus.UNAUTHORIZED, put("/api/admissions/" + admissionId, token, payload).getStatusCode(),
                "an unsupported PUT must currently surface as the 401 error-dispatch outcome, "
                        + "proving no update handler exists — intentional later change target");

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
    }

    /**
     * Today's create validation is only @NotBlank on every raw field —
     * including dischargedAt and status, which a caller must invent. Blank
     * values are rejected 400 through the shared ApiError body and persist
     * nothing.
     */
    @Test
    void admissionCreateCurrentlyDemandsEveryRawFieldIncludingDischargeAndStatus() {
        String token = login(ADMIN_USER);
        long admissionsBefore = dashboardCount("admissions", token);

        for (String blankedField : List.of("patientId", "admittedAt", "dischargedAt", "reason", "status")) {
            Map<String, Object> payload = new LinkedHashMap<>(admissionPayload("blanked"));
            payload.put(blankedField, "  ");
            ResponseEntity<Map<String, Object>> rejected = post("/api/admissions", token, payload);
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode(),
                    "a blank " + blankedField + " must return 400 under the current @NotBlank contract");
            Map<String, Object> error = rejected.getBody();
            assertNotNull(error, "validation failures must carry the shared ApiError body");
            assertEquals(400, ((Number) error.get("status")).intValue());
        }

        assertEquals(admissionsBefore, dashboardCount("admissions", token),
                "rejected creates must not persist any admission");
    }

    // ------------------------------------------------------------------
    // Emergency visit CRUD contract
    // ------------------------------------------------------------------

    /**
     * Emergency visits today: the same raw entity contract; the triage level
     * is an unconstrained raw string (no 1–5 rule yet — a deliberate plan2
     * Task 3 change target), the status is caller-invented, and patientId is
     * never verified.
     */
    @Test
    void emergencyVisitCrudExposesRawEntityContractWithUnconstrainedTriage() {
        String token = login(DOCTOR_USER);
        String unknownPatientId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of(
                "patientId", unknownPatientId,
                "arrivalAt", "yesterday-ish",
                "triageLevel", "9-out-of-5",
                "chiefComplaint", "synthetic unvalidated complaint " + suffix,
                "status", "IN_LIMBO");
        Set<String> entityContract = union(METADATA_KEYS, EMERGENCY_ENTITY_FIELDS);

        ResponseEntity<Map<String, Object>> created = post("/api/emergency-visits", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode(),
                "an out-of-range raw triage level and unknown patientId must currently be accepted");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(entityContract, body.keySet(),
                "the emergency-visit response must currently be the raw entity incl. persistence metadata");
        assertEquals("9-out-of-5", body.get("triageLevel"), "triage must currently be an unconstrained raw string");
        assertEquals(unknownPatientId, body.get("patientId"), "patientId must be stored verbatim without verification");
        String visitId = requireId(created);

        assertEquals(HttpStatus.OK, getMap("/api/emergency-visits/" + visitId, token).getStatusCode());
        ResponseEntity<List<Map<String, Object>>> list = getList("/api/emergency-visits", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "emergency list must carry a body");
        assertTrue(listBody.stream().anyMatch(v -> visitId.equals(String.valueOf(v.get("id")))),
                "the created visit must appear in the raw list");
        for (Map<String, Object> item : listBody) {
            assertEquals(entityContract, item.keySet(), "every list item must match the raw entity contract");
        }
        // Same error-dispatch artifact as the admission PUT: no transition handler
        // exists today, and the unsupported method surfaces as the 401 outcome.
        assertEquals(HttpStatus.UNAUTHORIZED,
                put("/api/emergency-visits/" + visitId, token, payload).getStatusCode(),
                "an unsupported PUT must currently surface as the 401 error-dispatch outcome, "
                        + "proving no transition handler exists — intentional later change target");

        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, token).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/emergency-visits/" + visitId, token).getStatusCode());
    }

    // ------------------------------------------------------------------
    // Invoice CRUD contract
    // ------------------------------------------------------------------

    /**
     * Invoices today: raw entity contract with no uniqueness on invoiceNumber
     * — two invoices may share a number — and no amount/currency/reference
     * validation of any kind. Both gaps are deliberate plan2 Task 4 change
     * targets.
     */
    @Test
    void invoiceCrudExposesRawEntityContractWithoutUniquenessOrValidation() {
        String token = login(BILLING_USER);
        String unknownPatientId = UUID.randomUUID().toString();
        String sharedNumber = "INV-RAW-" + suffix;
        Set<String> entityContract = union(METADATA_KEYS, INVOICE_ENTITY_FIELDS);

        ResponseEntity<Map<String, Object>> first = post("/api/invoices", token, Map.of(
                "patientId", unknownPatientId,
                "invoiceNumber", sharedNumber,
                "amount", "12.345,67-not-a-number",
                "currency", "XX",
                "status", "IMAGINED"));
        assertEquals(HttpStatus.OK, first.getStatusCode(),
                "arbitrary amount/currency/status strings and an unknown patientId must currently be accepted");
        Map<String, Object> firstBody = first.getBody();
        assertNotNull(firstBody);
        assertEquals(entityContract, firstBody.keySet(),
                "the invoice response must currently be the raw entity incl. persistence metadata");
        assertEquals("12.345,67-not-a-number", firstBody.get("amount"), "amount must be stored verbatim");
        assertEquals("XX", firstBody.get("currency"), "currency must currently be unconstrained");
        String firstId = requireId(first);

        ResponseEntity<Map<String, Object>> duplicate = post("/api/invoices", token, Map.of(
                "patientId", unknownPatientId,
                "invoiceNumber", sharedNumber,
                "amount", "0",
                "currency", "XX",
                "status", "IMAGINED"));
        assertEquals(HttpStatus.OK, duplicate.getStatusCode(),
                "a duplicate invoiceNumber must currently be accepted — no uniqueness exists yet");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/invoices", login(ADMIN_USER));
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> listBody = list.getBody();
        assertNotNull(listBody, "invoice list must carry a body");
        List<Map<String, Object>> twins = listBody.stream()
                .filter(i -> sharedNumber.equals(i.get("invoiceNumber")))
                .collect(Collectors.toList());
        assertEquals(2, twins.size(), "both invoices sharing a number must currently coexist");
        for (Map<String, Object> item : twins) {
            assertEquals(entityContract, item.keySet(), "every list item must match the raw entity contract");
        }

        assertEquals(HttpStatus.OK, delete("/api/invoices/" + firstId, token).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/invoices/" + firstId, login(ADMIN_USER)).getStatusCode());
    }

    // ------------------------------------------------------------------
    // Bed CRUD contract
    // ------------------------------------------------------------------

    /**
     * Beds today: the same raw CRUD shape; occupancyStatus is a free string
     * and patientId accepts even a non-UUID raw value, since nothing is ever
     * resolved. /api/beds is explicitly out of plan2 scope (§3) — this pins
     * the baseline only.
     */
    @Test
    void bedCrudExposesRawEntityContractWithUnverifiedPatientReference() {
        String token = login(RECEPTIONIST_USER);
        Map<String, Object> payload = Map.of(
                "ward", "ward-raw-" + suffix,
                "room", "room-not-validated",
                "bedNumber", "BED-RAW-01",
                "occupancyStatus", "OCCUPIED-BY-NOTHING",
                "patientId", "not-even-a-uuid");
        Set<String> entityContract = union(METADATA_KEYS, BED_ENTITY_FIELDS);

        ResponseEntity<Map<String, Object>> created = post("/api/beds", token, payload);
        assertEquals(HttpStatus.OK, created.getStatusCode(),
                "a non-UUID patientId and arbitrary raw strings must currently be accepted on beds");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(entityContract, body.keySet(),
                "the bed response must currently be the raw entity incl. persistence metadata");
        assertEquals("not-even-a-uuid", body.get("patientId"), "bed patientId must be stored verbatim");
        String bedId = requireId(created);

        assertEquals(HttpStatus.OK, getMap("/api/beds/" + bedId, token).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/beds/" + bedId, token).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/beds/" + bedId, token).getStatusCode());
    }

    // ------------------------------------------------------------------
    // Dashboard baseline
    // ------------------------------------------------------------------

    /**
     * Dashboard today: exactly five raw count keys with whole-table row-count
     * semantics — beds add no key, deletes feed straight back into the counts,
     * and there is no status awareness anywhere. Asserted with deltas over
     * test-created synthetic records only.
     */
    @Test
    void dashboardSummaryPinsExactKeySetAndRowCountSemantics() {
        String adminToken = login(ADMIN_USER);
        ResponseEntity<Map<String, Object>> before = getMap("/api/dashboard", adminToken);
        assertEquals(HttpStatus.OK, before.getStatusCode());
        Map<String, Object> beforeBody = before.getBody();
        assertNotNull(beforeBody, "dashboard response must carry a body");
        assertEquals(DASHBOARD_KEYS, beforeBody.keySet(), "the dashboard must currently expose exactly five keys");
        for (String key : DASHBOARD_KEYS) {
            assertInstanceOf(Number.class, beforeBody.get(key), "dashboard key '" + key + "' must be a raw count");
        }
        long patientsBefore = count(beforeBody, "patients");
        long appointmentsBefore = count(beforeBody, "appointments");
        long admissionsBefore = count(beforeBody, "admissions");
        long emergencyBefore = count(beforeBody, "emergencyVisits");
        long invoicesBefore = count(beforeBody, "invoices");

        String admissionId = requireId(post("/api/admissions", adminToken, admissionPayload("dash")));
        String visitId = requireId(post("/api/emergency-visits", adminToken, emergencyPayload("dash")));
        String invoiceId = requireId(post("/api/invoices", adminToken, invoicePayload("dash")));
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
        assertEquals(patientsBefore, count(afterBody, "patients"), "no patient was created");
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
     * Audit today: only successful CREATE and DELETE are recorded, one event
     * per mutation, with the session actor and the literal details strings.
     * Later tasks must hold every new mutation to exactly this bar.
     */
    @Test
    void createAndDeleteCurrentlyProduceOneAuditEventEachWithSessionActor() {
        String admissionId = requireId(post("/api/admissions", login(NURSE_USER), admissionPayload("audit")));
        String visitId = requireId(post("/api/emergency-visits", login(DOCTOR_USER), emergencyPayload("audit")));
        String invoiceId = requireId(post("/api/invoices", login(BILLING_USER), invoicePayload("audit")));
        String bedId = requireId(post("/api/beds", login(ADMIN_USER), bedPayload()));

        List<Map<String, Object>> events = auditEvents(login(ADMIN_USER));
        assertSingleEvent(events, "Admission", admissionId, "CREATE", NURSE_USER);
        assertSingleEvent(events, "EmergencyVisit", visitId, "CREATE", DOCTOR_USER);
        assertSingleEvent(events, "Invoice", invoiceId, "CREATE", BILLING_USER);
        assertSingleEvent(events, "Bed", bedId, "CREATE", ADMIN_USER);

        assertEquals(HttpStatus.OK, delete("/api/admissions/" + admissionId, login(NURSE_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/emergency-visits/" + visitId, login(DOCTOR_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/invoices/" + invoiceId, login(BILLING_USER)).getStatusCode());
        assertEquals(HttpStatus.OK, delete("/api/beds/" + bedId, login(ADMIN_USER)).getStatusCode());

        List<Map<String, Object>> afterDeletion = auditEvents(login(ADMIN_USER));
        assertSingleEvent(afterDeletion, "Admission", admissionId, "DELETE", NURSE_USER);
        assertSingleEvent(afterDeletion, "EmergencyVisit", visitId, "DELETE", DOCTOR_USER);
        assertSingleEvent(afterDeletion, "Invoice", invoiceId, "DELETE", BILLING_USER);
        assertSingleEvent(afterDeletion, "Bed", bedId, "DELETE", ADMIN_USER);
    }

    private void assertSingleEvent(List<Map<String, Object>> events, String resourceType, String resourceId,
                                   String action, String actor) {
        List<Map<String, Object>> matches = events.stream()
                .filter(e -> resourceType.equals(e.get("resourceType")) && resourceId.equals(e.get("resourceId"))
                        && action.equals(e.get("action")))
                .collect(Collectors.toList());
        assertEquals(1, matches.size(),
                "exactly one " + action + " audit event must exist for " + resourceType + " " + resourceId);
        Map<String, Object> event = matches.get(0);
        assertEquals(action, event.get("action"));
        assertEquals(actor, event.get("actor"), "the audit actor must be the authenticated session user");
        assertEquals("CREATE".equals(action) ? "created" : "deleted", event.get("details"),
                "the current audit details are the literal create/delete strings");
        assertNotNull(event.get("occurredAt"), "audit events must carry occurredAt");
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

    private Map<String, Object> admissionPayload(String tag) {
        return Map.of(
                "patientId", UUID.randomUUID().toString(),
                "admittedAt", "2031-01-01T08:00:00-" + tag,
                "dischargedAt", "2031-02-01T10:00:00-" + tag,
                "reason", "synthetic admission " + suffix + " " + tag,
                "status", "ADMITTED-RAW");
    }

    private Map<String, Object> emergencyPayload(String tag) {
        return Map.of(
                "patientId", UUID.randomUUID().toString(),
                "arrivalAt", "2031-01-01T09:00:00-" + tag,
                "triageLevel", "3",
                "chiefComplaint", "synthetic complaint " + suffix + " " + tag,
                "status", "WAITING-RAW");
    }

    private Map<String, Object> invoicePayload(String tag) {
        return Map.of(
                "patientId", UUID.randomUUID().toString(),
                "invoiceNumber", "INV-" + suffix + "-" + tag,
                "amount", "10.00",
                "currency", "USD",
                "status", "DRAFT-RAW");
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
        assertInstanceOf(Number.class, value, "dashboard key '" + key + "' must be a raw count");
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

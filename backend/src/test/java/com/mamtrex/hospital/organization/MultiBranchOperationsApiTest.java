package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionBedAssignment;
import com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 3 Task 2 contract suite (docs/plan3.md Task 2): the single synthetic
 * organization, its ADMIN-only branch surface, and branch-owned department
 * DTO contracts over real HTTP against an isolated in-memory H2 database
 * with synthetic disposable records only.
 *
 * <p>Retained Task 1 characterizations (login shape, acting-context
 * indifference, appointment overlap, whole-table dashboard, context-free
 * audit events) keep pinning today's unrelated weaknesses for their later
 * tasks. Department and bed rows with no branch are the deliberate legacy
 * transition seam: this suite proves the normalized contracts never expose
 * or mutate them. The earlier raw-bed characterization pin was replaced by
 * the Task 6 normalized bed inventory contract below, mirroring how Task 3
 * replaced the Task 1 login-shape pin it intentionally changed.</p>
 *
 * <p>Every test keys its synthetic records by a unique per-instance suffix
 * and asserts list outcomes scoped to that suffix, so the shared database
 * never couples one test to another's rows or run order.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:multibranch-ops-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + MultiBranchOperationsApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + MultiBranchOperationsApiTest.TEST_ACCOUNT_PASSWORD
})
class MultiBranchOperationsApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-multibranch-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-multibranch-01";

    private static final String ADMIN = "mbops-admin";
    private static final String NURSE = "mbops-nurse";

    /** Stable synthetic test organization key; provisioned via the real repository (no create endpoint exists). */
    private static final String TEST_ORG_CODE = "MBOPS-ORG";

    /** Stable deterministic default branch the synthetic accounts act on. */
    private static final String TEST_DEFAULT_BRANCH_CODE = "MBOPS-BR-DEFAULT";

    /** Exact Task 2 branch DTO allowlist. */
    private static final Set<String> BRANCH_DTO_KEYS =
            Set.of("id", "organizationId", "code", "name", "locationLabel", "active");

    /** Exact Task 2 department DTO allowlist. */
    private static final Set<String> DEPARTMENT_DTO_KEYS =
            Set.of("id", "branchId", "code", "name", "specialty", "location");

    /** Shared safe ApiError contract key set (404/409 bodies). */
    private static final Set<String> API_ERROR_KEYS = Set.of("timestamp", "status", "error", "message", "path");

    /** Today's exact flat dashboard key set; no branch/network structure exists. */
    private static final Set<String> DASHBOARD_KEYS = Set.of(
            "patients", "appointments", "admissions", "emergencyVisits", "invoices",
            "openAdmissions", "activeEmergencyVisits",
            "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<Map<String, Object>>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<List<Map<String, Object>>>() {};

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
    DepartmentRepository departments;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    PatientRepository patients;

    @Autowired
    StaffMemberRepository staffMembers;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    BedRepository beds;

    @Autowired
    AdmissionRepository admissions;

    @Autowired
    AdmissionBedAssignmentRepository bedAssignments;

    /** Unique synthetic suffix per test instance keeps every record disposable and scoped. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Task 3 seeding: the synthetic accounts log in through explicit enabled
     * acting assignments (ADMIN organization-scope, NURSE bound to the
     * deterministic default branch) — no global-role fallback exists.
     */
    @BeforeEach
    void seedDisposableAccountsAndOrganization() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        if (accounts.findByUsername(NURSE).isEmpty()) {
            accounts.save(new UserAccount(NURSE, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        }
        ensureAssignmentContext();
    }

    /** Loads or creates the hierarchy foundation plus the accounts' acting assignments. */
    private void ensureAssignmentContext() {
        ensureOrganization();
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseThrow();
        Branch defaultBranch = branches.findByOrganizationIdAndCode(org.getId(), TEST_DEFAULT_BRANCH_CODE)
                .orElseGet(() -> branches.save(new Branch(org, TEST_DEFAULT_BRANCH_CODE,
                        "Synthetic Default Branch", "0 Default Circle")));
        ensureAssignment(ADMIN, Role.ADMIN, AssignmentScope.ORGANIZATION, org, null);
        ensureAssignment(NURSE, Role.NURSE, AssignmentScope.BRANCH, org, defaultBranch);
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
    // 1. Hierarchy routes are ADMIN-only over real HTTP.
    // ------------------------------------------------------------------

    /** One route probe shared by the three authorization outcomes. */
    private record RouteProbe(HttpMethod method, String path, Map<String, Object> adminBody) {
    }

    static Stream<RouteProbe> hierarchyRoutes() {
        return Stream.of(
                new RouteProbe(HttpMethod.GET, "/api/organization", null),
                new RouteProbe(HttpMethod.GET, "/api/branches", null),
                new RouteProbe(HttpMethod.POST, "/api/branches", Map.of(
                        "code", "MBOPS-RT-" + UUID.randomUUID().toString().substring(0, 8),
                        "name", "Synthetic Route Probe Branch",
                        "locationLabel", "1 Route Probe Way")),
                new RouteProbe(HttpMethod.GET, "/api/departments", null),
                new RouteProbe(HttpMethod.POST, "/api/departments", Map.of()));
    }

    /**
     * Security contract: every hierarchy route authorizes ADMIN (the outcome
     * may be 201/200/400/404 depending on the probe, but never 401/403),
     * denies an authenticated non-ADMIN with the shared 403, and leaves
     * anonymous callers at the shared 401.
     */
    @ParameterizedTest
    @MethodSource("hierarchyRoutes")
    void hierarchyRoutesAuthorizeOnlyAdminAccountsOverRealHttp(RouteProbe route) {
        ResponseEntity<String> admin = rawExchange(route.method(), route.path(), login(ADMIN), route.adminBody());
        assertFalse(admin.getStatusCode() == HttpStatus.UNAUTHORIZED || admin.getStatusCode() == HttpStatus.FORBIDDEN,
                "ADMIN must pass authorization on " + route.method() + " " + route.path()
                        + " (observed " + admin.getStatusCode() + ")");
        assertEquals(HttpStatus.FORBIDDEN,
                rawExchange(route.method(), route.path(), login(NURSE), route.adminBody()).getStatusCode(),
                "an authenticated non-ADMIN must be denied on " + route.method() + " " + route.path());
        assertEquals(HttpStatus.UNAUTHORIZED,
                rawExchange(route.method(), route.path(), null, route.adminBody()).getStatusCode(),
                "an anonymous caller must stay unauthenticated on " + route.method() + " " + route.path());
    }

    // ------------------------------------------------------------------
    // 2. The absent-organization safe 404 contract.
    // ------------------------------------------------------------------

    /**
     * Task 3 translation of the Task 2 absent-organization contract: with
     * the whole hierarchy wiped, the organization 404 state is no longer
     * observable by an authorized caller over HTTP — authentication itself
     * requires an enabled assignment whose organization and selected branch
     * exist, so the wiped state fails closed (old token 401, re-login 401)
     * and anonymous callers stay on the shared 401 boundary. Once the
     * hierarchy and assignments are restored, the Task 2 present-state
     * contracts hold again: authorized GET /api/organization answers 200 and
     * a branch can be created through the public contract (an orphan branch
     * can never exist because no caller can authenticate while the sole
     * organization is absent).
     */
    @Test
    void wipedHierarchyFailsClosedAndTheRestoredHierarchyRecoversTheTask2Contracts() {
        String token = login(ADMIN);
        wipeHierarchy();

        assertEquals(HttpStatus.UNAUTHORIZED, getJson("/api/organization", token).getStatusCode(),
                "a token whose assignment organization was wiped is unauthenticated immediately");
        ResponseEntity<Map<String, Object>> refusedLogin = postJson("/api/auth/login", null, Map.of(
                "username", ADMIN,
                "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.UNAUTHORIZED, refusedLogin.getStatusCode(),
                "without the hierarchy and assignments, login fails closed");
        assertEquals(HttpStatus.UNAUTHORIZED, getJson("/api/organization", null).getStatusCode(),
                "anonymous callers stay on the shared 401 boundary in the wiped state");

        ensureAssignmentContext();
        String recoveredToken = login(ADMIN);
        ResponseEntity<Map<String, Object>> orgResponse = getJson("/api/organization", recoveredToken);
        assertEquals(HttpStatus.OK, orgResponse.getStatusCode(),
                "the restored hierarchy answers the authorized organization read again");
        ResponseEntity<Map<String, Object>> branchResponse = postJson("/api/branches", recoveredToken,
                Map.of("code", "MBOPS-RECOVER-" + suffix, "name", "Recovery Probe", "locationLabel", "Back"));
        assertTrue(branchResponse.getStatusCode().is2xxSuccessful(),
                "branch creation works through the public contract once the sole organization exists");
    }

    // ------------------------------------------------------------------
    // 3. Organization DTO allowlist, active-only, deterministic order.
    // ------------------------------------------------------------------

    /**
     * GET /api/organization returns exactly the allowlisted organization
     * fields plus only ACTIVE branch DTOs in deterministic code order — a
     * deactivated branch must never appear, proving the active filter is
     * real and not incidental to an all-active table.
     */
    @Test
    void organizationEndpointReturnsTheDtoAllowlistWithOnlyActiveBranchesInDeterministicOrder() {
        String token = login(ADMIN);
        createBranch(token, suffix + "-c");
        createBranch(token, suffix + "-a");
        createBranch(token, suffix + "-b");
        Branch hidden = branchByCode(suffix + "-b");
        hidden.deactivate();
        branches.save(hidden);

        ResponseEntity<Map<String, Object>> response = getJson("/api/organization", token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(Set.of("id", "code", "name", "activeBranches"), body.keySet(),
                "the organization response must be exactly the allowlisted fields");
        assertEquals(TEST_ORG_CODE, body.get("code"));

        List<Map<String, Object>> active = branchList(body.get("activeBranches"));
        assertTrue(active.stream().allMatch(row -> BRANCH_DTO_KEYS.equals(row.keySet())),
                "every nested branch must carry exactly the branch DTO allowlist");
        assertTrue(active.stream().noneMatch(row -> (suffix + "-b").equals(row.get("code"))),
                "the deactivated branch must be excluded from the organization view");
        List<String> suiteCodes = active.stream().map(row -> String.valueOf(row.get("code")))
                .filter(code -> code.startsWith(suffix)).toList();
        assertEquals(List.of(suffix + "-a", suffix + "-c"), suiteCodes,
                "this suite's active branches must appear in deterministic code order");
        assertTrue(active.stream().allMatch(row -> Boolean.TRUE.equals(row.get("active"))));
    }

    // ------------------------------------------------------------------
    // 4. Branch list/get DTO allowlists in deterministic order.
    // ------------------------------------------------------------------

    /**
     * GET /api/branches and GET /api/branches/{id} expose exactly the
     * branch DTO allowlist; the flat review list keeps a deterministic code
     * order and — unlike the organization view — remains the hierarchy
     * review surface that also shows inactive branches.
     */
    @Test
    void branchListAndBranchGetExposeAllowlistedDtosInDeterministicOrder() {
        String token = login(ADMIN);
        Map<String, Object> first = createBranch(token, suffix + "-x");
        createBranch(token, suffix + "-y");
        createBranch(token, suffix + "-z");
        Branch inactive = branchByCode(suffix + "-z");
        inactive.deactivate();
        branches.save(inactive);

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/branches", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> rows = list.getBody();
        assertNotNull(rows, "the branch list must carry a body");
        assertTrue(rows.stream().allMatch(row -> BRANCH_DTO_KEYS.equals(row.keySet())),
                "every listed branch must carry exactly the DTO allowlist");
        List<String> suiteCodes = rows.stream().map(row -> String.valueOf(row.get("code")))
                .filter(code -> code.startsWith(suffix)).toList();
        assertEquals(List.of(suffix + "-x", suffix + "-y", suffix + "-z"), suiteCodes,
                "this suite's branches must appear in deterministic code order, inactive included");
        assertTrue(rows.stream()
                        .filter(row -> (suffix + "-z").equals(row.get("code")))
                        .allMatch(row -> Boolean.FALSE.equals(row.get("active"))),
                "the inactive branch stays visible on the review list with active=false");

        ResponseEntity<Map<String, Object>> single = getJson("/api/branches/" + first.get("id"), token);
        assertEquals(HttpStatus.OK, single.getStatusCode());
        assertNotNull(single.getBody());
        assertEquals(BRANCH_DTO_KEYS, single.getBody().keySet(), "get-by-id must return exactly the DTO allowlist");
        assertEquals(first.get("id"), single.getBody().get("id"));
        assertEquals(suffix + "-x", single.getBody().get("code"));
    }

    // ------------------------------------------------------------------
    // 5. Branch create: 201, trimming, allowlisted DTO.
    // ------------------------------------------------------------------

    /**
     * POST /api/branches accepts exactly {code, name, locationLabel}, trims
     * every accepted value, returns 201 with the allowlisted DTO bound to
     * the sole organization, and the same DTO comes back from get-by-id.
     */
    @Test
    void branchCreateTrimsInputsReturns201AndTheAllowlistedDto() {
        String token = login(ADMIN);
        ResponseEntity<Map<String, Object>> created = postJson("/api/branches", token, Map.of(
                "code", "  " + suffix + "-trim  ",
                "name", "  Synthetic Trimmed Branch  ",
                "locationLabel", "  2 Trimmed Avenue  "));
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), "a valid branch create must answer 201");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(BRANCH_DTO_KEYS, body.keySet(), "the created branch must be exactly the DTO allowlist");
        assertEquals(suffix + "-trim", body.get("code"), "the branch code must be stored trimmed");
        assertEquals("Synthetic Trimmed Branch", body.get("name"));
        assertEquals("2 Trimmed Avenue", body.get("locationLabel"));
        assertEquals(Boolean.TRUE, body.get("active"), "a branch is active by default");
        assertEquals(organizations.findByCode(TEST_ORG_CODE).orElseThrow().getId().toString(),
                String.valueOf(body.get("organizationId")), "the branch must bind to the sole organization");

        ResponseEntity<Map<String, Object>> fetched = getJson("/api/branches/" + body.get("id"), token);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertNotNull(fetched.getBody());
        assertEquals(suffix + "-trim", fetched.getBody().get("code"), "get-by-id must see the same trimmed row");
    }

    // ------------------------------------------------------------------
    // 6. Branch create validation: 400.
    // ------------------------------------------------------------------

    /**
     * Whitespace-only and missing fields never reach the service: each
     * malformed branch create is the shared 400 validation contract.
     */
    @ParameterizedTest
    @ValueSource(strings = {"code", "name", "locationLabel"})
    void branchCreateValidationRejectsBlankAndMissingFieldsWith400(String missingField) {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "code", suffix + "-valid",
                "name", "Synthetic Valid Branch",
                "locationLabel", "3 Valid Avenue"));
        payload.remove(missingField);
        assertEquals(HttpStatus.BAD_REQUEST, postJson("/api/branches", login(ADMIN), payload).getStatusCode(),
                "missing " + missingField + " must be the shared 400 validation contract");

        payload.put(missingField, "   ");
        assertEquals(HttpStatus.BAD_REQUEST, postJson("/api/branches", login(ADMIN), payload).getStatusCode(),
                "a whitespace-only " + missingField + " must be the shared 400 validation contract");
    }

    // ------------------------------------------------------------------
    // 7. Duplicate branch code: safe 409, exactly one success audit, none on failure.
    // ------------------------------------------------------------------

    /**
     * A duplicate branch code inside the organization is the established
     * safe 409 contract with the controlled service message; the successful
     * create records exactly one CREATE audit event and the refused
     * duplicate records none.
     */
    @Test
    void duplicateBranchCodeIsConflict409WithTheSafeMessageAndNoAuditEvent() {
        String token = login(ADMIN);
        long auditBefore = auditEventCount("Branch", "CREATE");
        createBranch(token, suffix + "-dup");
        assertEquals(auditBefore + 1, auditEventCount("Branch", "CREATE"),
                "the successful create must record exactly one Branch CREATE audit event");

        ResponseEntity<Map<String, Object>> conflict = postJson("/api/branches", token, Map.of(
                "code", suffix + "-dup",
                "name", "Different Name Same Code",
                "locationLabel", "Elsewhere"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), "the duplicate code must be refused");
        assertNotNull(conflict.getBody());
        assertEquals(API_ERROR_KEYS, conflict.getBody().keySet(), "the conflict must use the shared safe contract");
        assertEquals("Branch code already exists in this organization", conflict.getBody().get("message"),
                "the conflict must carry the controlled service message, never persistence internals");
        assertEquals(auditBefore + 1, auditEventCount("Branch", "CREATE"),
                "the refused duplicate must record no audit event");
    }

    // ------------------------------------------------------------------
    // 8. Unknown branch id: shared 404.
    // ------------------------------------------------------------------

    @Test
    void unknownBranchIdReturnsTheShared404() {
        assertEquals(HttpStatus.NOT_FOUND,
                getJson("/api/branches/" + UUID.randomUUID(), login(ADMIN)).getStatusCode(),
                "an absent branch must answer the shared 404 contract");
    }

    // ------------------------------------------------------------------
    // 9. Departments require a verified branch; DTO allowlist; trim.
    // ------------------------------------------------------------------

    /**
     * Department create accepts exactly {branchId, code, name, specialty,
     * location}, resolves the branch reference (missing reference is the
     * shared 400, unknown branch the shared 404), trims every accepted
     * value, and returns the allowlisted DTO with the resolved branchId.
     * Failed attempts record no audit event.
     */
    @Test
    void departmentsRequireAVerifiedBranchAndReturnTheAllowlistedDto() {
        String token = login(ADMIN);
        String branchId = String.valueOf(createBranch(token, suffix + "-dep").get("id"));

        ResponseEntity<Map<String, Object>> created = postJson("/api/departments", token, Map.of(
                "branchId", branchId,
                "code", "  " + suffix + "-dtrim  ",
                "name", "  Synthetic Trimmed Department  ",
                "specialty", "  general  ",
                "location", "  4 Trimmed Ward  "));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "a valid department create must succeed");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(DEPARTMENT_DTO_KEYS, body.keySet(),
                "the department response must be exactly the DTO allowlist: no timestamps, version, or raw entity");
        assertEquals(branchId, String.valueOf(body.get("branchId")), "the DTO must carry the resolved branch reference");
        assertEquals(suffix + "-dtrim", body.get("code"), "the department code must be stored trimmed");
        assertEquals("Synthetic Trimmed Department", body.get("name"));
        assertEquals("general", body.get("specialty"));
        assertEquals("4 Trimmed Ward", body.get("location"));

        long auditAfterSuccess = auditEventCount("Department", "CREATE");
        assertEquals(HttpStatus.BAD_REQUEST, postJson("/api/departments", token,
                        Map.of("code", suffix + "-nobranch", "name", "n", "specialty", "s", "location", "l")).getStatusCode(),
                "a missing branchId must be the shared 400 validation contract");
        assertEquals(HttpStatus.NOT_FOUND, postJson("/api/departments", token, Map.of(
                        "branchId", UUID.randomUUID().toString(),
                        "code", suffix + "-ghost", "name", "Ghost Department", "specialty", "s", "location", "l")).getStatusCode(),
                "a nonexistent branch reference must be the shared 404 (referential integrity)");
        assertEquals(auditAfterSuccess, auditEventCount("Department", "CREATE"),
                "failed department creates must record no audit event");
    }

    // ------------------------------------------------------------------
    // 10. Inactive branches cannot own new departments.
    // ------------------------------------------------------------------

    /**
     * A verified branch reference is not enough: a deactivated branch must
     * be refused as a department owner with the safe conflict contract and
     * no audit event.
     */
    @Test
    void inactiveBranchesCannotOwnNewDepartments() {
        String token = login(ADMIN);
        createBranch(token, suffix + "-closed");
        Branch inactive = branchByCode(suffix + "-closed");
        inactive.deactivate();
        branches.save(inactive);

        long auditBefore = auditEventCount("Department", "CREATE");
        ResponseEntity<Map<String, Object>> refused = postJson("/api/departments", token, Map.of(
                "branchId", inactive.getId().toString(),
                "code", suffix + "-closed-dep", "name", "Closed Branch Department",
                "specialty", "s", "location", "l"));
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(),
                "an inactive branch must not own new departments");
        assertNotNull(refused.getBody());
        assertTrue(String.valueOf(refused.getBody().get("message")).contains("not active"),
                "the refusal must carry the controlled service message");
        assertEquals(auditBefore, auditEventCount("Department", "CREATE"),
                "the refused create must record no audit event");
    }

    // ------------------------------------------------------------------
    // 11. Department list: assigned rows only, optional filter, unknown filter 404.
    // ------------------------------------------------------------------

    /**
     * The department list is assigned-rows-only: a pre-existing null-branch
     * legacy row is never disclosed. The optional branchId filter scopes to
     * one branch deterministically, and an unknown branch filter is the
     * shared 404 — never a silently empty list.
     */
    @Test
    void departmentListIsAssignedOnlySupportsTheOptionalBranchFilterAndHidesUnknownBranches() {
        String token = login(ADMIN);
        departments.save(new Department(null, suffix + "-legacy", "Legacy Unassigned Department", "s", "l"));
        String branchA = String.valueOf(createBranch(token, suffix + "-ba").get("id"));
        String branchB = String.valueOf(createBranch(token, suffix + "-bb").get("id"));
        postJson("/api/departments", token, departmentPayload(suffix + "-da", branchA));
        postJson("/api/departments", token, departmentPayload(suffix + "-db", branchB));

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/departments", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> rows = list.getBody();
        assertNotNull(rows, "the department list must carry a body");
        assertTrue(rows.stream().allMatch(row -> DEPARTMENT_DTO_KEYS.equals(row.keySet())),
                "every listed department must carry exactly the DTO allowlist");
        List<String> suiteCodes = rows.stream().map(row -> String.valueOf(row.get("code")))
                .filter(code -> code.startsWith(suffix)).toList();
        assertEquals(List.of(suffix + "-da", suffix + "-db"), suiteCodes.stream().sorted().toList(),
                "the unfiltered list must contain exactly this suite's assigned rows");
        assertFalse(suiteCodes.contains(suffix + "-legacy"),
                "the unassigned legacy row must never be disclosed by the list");

        ResponseEntity<List<Map<String, Object>>> scopedA = getList("/api/departments?branchId=" + branchA, token);
        assertEquals(HttpStatus.OK, scopedA.getStatusCode());
        assertNotNull(scopedA.getBody());
        assertEquals(List.of(suffix + "-da"),
                scopedA.getBody().stream().map(row -> String.valueOf(row.get("code"))).toList(),
                "the branch filter must scope to exactly that branch's rows");

        ResponseEntity<List<Map<String, Object>>> scopedB = getList("/api/departments?branchId=" + branchB, token);
        assertNotNull(scopedB.getBody());
        assertEquals(List.of(suffix + "-db"),
                scopedB.getBody().stream().map(row -> String.valueOf(row.get("code"))).toList(),
                "the same code on a different branch is a distinct row (cross-branch codes allowed)");

        assertEquals(HttpStatus.NOT_FOUND,
                rawExchange(HttpMethod.GET, "/api/departments?branchId=" + UUID.randomUUID(), token, null).getStatusCode(),
                "an unknown branch filter must be the shared 404, never a silently empty list");
    }

    // ------------------------------------------------------------------
    // 12. Same code across branches allowed; same-branch duplicate 409.
    // ------------------------------------------------------------------

    /**
     * Department code uniqueness is scoped to one branch: the same code in
     * two branches creates two distinct rows, while repeating it inside one
     * branch is the safe 409 with exactly one audited success per real
     * create and no audit event for the refusal.
     */
    @Test
    void sameDepartmentCodeIsAllowedAcrossBranchesButRefusedInsideOneBranch() {
        String token = login(ADMIN);
        String branchA = String.valueOf(createBranch(token, suffix + "-ca").get("id"));
        String branchB = String.valueOf(createBranch(token, suffix + "-cb").get("id"));

        long auditBefore = auditEventCount("Department", "CREATE");
        ResponseEntity<Map<String, Object>> inA = postJson("/api/departments", token, departmentPayload("shared-code", branchA));
        assertTrue(inA.getStatusCode().is2xxSuccessful(), "the first branch must accept the code");
        ResponseEntity<Map<String, Object>> inB = postJson("/api/departments", token, departmentPayload("shared-code", branchB));
        assertTrue(inB.getStatusCode().is2xxSuccessful(), "the second branch must accept the same code");
        assertNotNull(inA.getBody());
        assertNotNull(inB.getBody());
        assertNotEquals(String.valueOf(inA.getBody().get("id")), String.valueOf(inB.getBody().get("id")),
                "the same code in two branches must be two distinct persisted rows");
        assertEquals(auditBefore + 2, auditEventCount("Department", "CREATE"),
                "each real create must record exactly one audit event");

        ResponseEntity<Map<String, Object>> conflict = postJson("/api/departments", token, departmentPayload("shared-code", branchA));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), "the same-branch repeat must be refused");
        assertNotNull(conflict.getBody());
        assertEquals("Department code already exists in this branch", conflict.getBody().get("message"),
                "the refusal must carry the controlled service message");
        assertEquals(auditBefore + 2, auditEventCount("Department", "CREATE"),
                "the refusal must record no audit event");
    }

    // ------------------------------------------------------------------
    // 13. Legacy null-branch rows are invisible and untouchable.
    // ------------------------------------------------------------------

    /**
     * The normalized contract never exposes or mutates an unassigned legacy
     * row: get and delete answer the shared 404, and the row itself stays
     * untouched in the store (same code, branch still null).
     */
    @Test
    void unassignedLegacyDepartmentsAreInvisibleAndUntouchableThroughTheNormalizedContract() {
        String token = login(ADMIN);
        Department legacy = departments.save(
                new Department(null, suffix + "-hidden", "Hidden Legacy Department", "s", "l"));

        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/departments/" + legacy.getId(), token).getStatusCode(),
                "get must not disclose an unassigned legacy row");
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/departments/" + legacy.getId(), token).getStatusCode(),
                "delete must not mutate an unassigned legacy row");

        Department reloaded = departments.findById(legacy.getId()).orElseThrow();
        assertEquals(suffix + "-hidden", reloaded.getCode(), "the legacy row must stay untouched");
        assertNull(reloaded.getBranch(), "the legacy row must remain unassigned");
    }

    // ------------------------------------------------------------------
    // 14. Successful department lifecycle audits exactly once per operation.
    // ------------------------------------------------------------------

    /**
     * A successful department create and delete each own exactly one audit
     * event under the existing conventions, located by resource id — not by
     * list ordering.
     */
    @Test
    void successfulDepartmentLifecycleRecordsExactlyOneAuditEventPerOperation() {
        String token = login(ADMIN);
        String branchId = String.valueOf(createBranch(token, suffix + "-audit").get("id"));
        Map<String, Object> created = postJson("/api/departments", token, departmentPayload("audit", branchId)).getBody();
        assertNotNull(created);
        String id = String.valueOf(created.get("id"));
        assertEquals(1, auditEventsFor("Department", id, "CREATE"),
                "the create must own exactly one CREATE audit event");

        assertEquals(HttpStatus.OK, delete("/api/departments/" + id, token).getStatusCode());
        assertEquals(1, auditEventsFor("Department", id, "DELETE"),
                "the delete must own exactly one DELETE audit event");
        assertEquals(1, auditEventsFor("Department", id, "CREATE"),
                "the create event must be untouched by the delete");
        assertTrue(getList("/api/departments", token).getBody().stream()
                        .noneMatch(row -> id.equals(String.valueOf(row.get("id")))),
                "the deleted department must leave the normalized list");
    }

    // ------------------------------------------------------------------
    // 15. DB unique constraints are the concurrency backstop.
    // ------------------------------------------------------------------

    /**
     * Beyond the service pre-checks, the DB unique constraints must refuse a
     * duplicated (organization, code) branch and a duplicated (branch, code)
     * department through the real repositories — asserted as the translated
     * integrity violation, never via provider message text.
     */
    @Test
    void databaseUniqueConstraintsBackstopHierarchyUniqueness() {
        var org = organizations.findByCode(TEST_ORG_CODE).orElseThrow();
        String code = suffix + "-con";
        branches.save(new Branch(org, code, "Constraint Branch", "1 Constraint Way"));
        assertThrows(DataIntegrityViolationException.class,
                () -> branches.save(new Branch(org, code, "Duplicate Branch", "2 Constraint Way")),
                "the (organization_id, code) unique constraint must refuse the duplicate branch");

        Branch saved = branches.findByOrganizationIdAndCode(org.getId(), code).orElseThrow();
        departments.save(new Department(saved, code + "-d", "Constraint Department", "s", "l"));
        assertThrows(DataIntegrityViolationException.class,
                () -> departments.save(new Department(saved, code + "-d", "Duplicate Department", "s", "l")),
                "the (branch_id, code) unique constraint must refuse the duplicate department");
    }

    // ------------------------------------------------------------------
    // 16. Task 3 contract: login carries the acting-assignment shape.
    // ------------------------------------------------------------------

    /**
     * Task 3 contract (replaces the Task 1 global-role pin that Task 3
     * intentionally changed): the login response carries exactly the six-key
     * allowlist — token, token type, username, the single selected assignment
     * role, the deterministic assignment list, and the acting context — and
     * never the password hash or the legacy role union.
     */
    @Test
    void loginResponseCarriesTheActingAssignmentAllowlistWithOneSelectedRole() {
        ResponseEntity<Map<String, Object>> response = postJson("/api/auth/login", null, Map.of(
                "username", ADMIN,
                "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the login response must carry a body");
        assertEquals(Set.of("accessToken", "tokenType", "username", "roles", "assignments", "actingContext"),
                body.keySet(),
                "the Task 3 login response is the acting-assignment shape with assignments and actingContext");
        assertEquals("Bearer", body.get("tokenType"));
        assertEquals(List.of("ADMIN"), body.get("roles"),
                "roles carries only the selected assignment role, never the legacy union");
        assertFalse(body.containsKey("passwordHash"), "the password hash must never be exposed");

        assertInstanceOf(List.class, body.get("assignments"));
        List<?> assignmentsView = (List<?>) body.get("assignments");
        assertEquals(1, assignmentsView.size(), "the account's enabled assignments are listed");
        assertInstanceOf(Map.class, body.get("actingContext"));
        Map<?, ?> context = (Map<?, ?>) body.get("actingContext");
        assertEquals(Set.of("username", "assignmentId", "role", "scope", "organizationId", "branchId",
                "departmentId"), context.keySet(),
                "the acting context is the strict allowlist value");
        assertEquals(ADMIN, context.get("username"));
        assertEquals("ORGANIZATION", context.get("scope"));
        assertEquals("ADMIN", context.get("role"));
        assertNotNull(context.get("branchId"),
                "login deterministically binds the organization scope to the first active branch");
    }

    // ------------------------------------------------------------------
    // 17. Task 3 contract: context headers carry no authority.
    // ------------------------------------------------------------------

    /**
     * Task 3 contract (replaces the Task 1 pin that Task 3 intentionally
     * changed): a plain bearer token authorizes reads through its server-
     * derived acting assignment, and proposed context headers can neither
     * elevate authority nor change any response — the replacement-token
     * endpoint is the only switching mechanism.
     */
    @Test
    void contextHeadersCarryNoAuthorityAndReadsFollowOnlyTheServerDerivedAssignment() {
        String token = login(ADMIN);
        String patientId = createVerifiedPatientId(token, "ctx");

        ResponseEntity<Map<String, Object>> plain = getJson("/api/patients/" + patientId, token);
        assertEquals(HttpStatus.OK, plain.getStatusCode(),
                "a valid token authorizes the read through its acting assignment with no extra headers");
        Map<String, Object> plainBody = plain.getBody();
        assertNotNull(plainBody);
        assertEquals(patientId, String.valueOf(plainBody.get("id")),
                "the plain-token read must reach the created synthetic record");

        HttpHeaders proposedContext = new HttpHeaders();
        proposedContext.setBearerAuth(token);
        proposedContext.set("X-Acting-Assignment-Id", "00000000-0000-0000-0000-00000000000a");
        proposedContext.set("X-Acting-Branch-Id", "00000000-0000-0000-0000-00000000000b");
        proposedContext.set("X-Acting-Role", "NURSE");
        ResponseEntity<Map<String, Object>> decorated = rest.exchange(
                "/api/patients/" + patientId, HttpMethod.GET, new HttpEntity<>(proposedContext), MAP);
        assertEquals(HttpStatus.OK, decorated.getStatusCode(),
                "proposed context headers must not break the read");
        assertEquals(plainBody, decorated.getBody(),
                "arbitrary context headers have no scoping or authority effect on the read");
        HttpHeaders forged = new HttpHeaders();
        forged.setBearerAuth(login(NURSE));
        forged.set("X-Acting-Role", "ADMIN");
        assertEquals(HttpStatus.FORBIDDEN,
                rest.exchange("/api/audit", HttpMethod.GET, new HttpEntity<>(forged), String.class).getStatusCode(),
                "a forged ADMIN role header cannot elevate a non-admin caller onto an ADMIN-only route");
    }

    // ------------------------------------------------------------------
    // 18. Task 6: normalized branch-owned bed inventory lifecycle.
    // ------------------------------------------------------------------

    /** Exact Task 6 bed DTO allowlist. */
    private static final Set<String> BED_DTO_KEYS =
            Set.of("id", "branchId", "ward", "room", "bedNumber", "occupancyStatus");

    /** Creates a bed through the public contract while acting on its owning branch. */
    private Map<String, Object> createBed(String token, String ward, String room, String bedNumber) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/beds", token, Map.of(
                "ward", ward,
                "room", room,
                "bedNumber", bedNumber));
        assertTrue(created.getStatusCode().is2xxSuccessful(),
                "a valid normalized bed create must succeed");
        return created.getBody();
    }

    /** Synthetic normalized bed create body, unique per tag within this run. */
    private Map<String, Object> bedCreatePayload(String tag) {
        return Map.of(
                "ward", "WARD-MB-" + suffix,
                "room", "ROOM-MB-" + suffix + "-" + tag,
                "bedNumber", "BED-MB-" + suffix + "-" + tag);
    }

    /** Persists a bed row directly as an admission would own it (Task 7 seam). */
    private Bed persistBedWithStatus(Branch branch, String tag, String status) {
        Bed bed = new Bed(branch, "WARD-R-" + suffix, "ROOM-R-" + suffix + "-" + tag,
                "BED-R-" + suffix + "-" + tag);
        if ("OCCUPIED".equals(status)) {
            bed.markOccupiedByAdmission();
        } else if ("AVAILABLE".equals(status)) {
            bed.releaseByAdmission();
        } else {
            throw new IllegalArgumentException("This helper models admission-owned statuses only");
        }
        return beds.save(bed);
    }

    /**
     * Task 6 lifecycle contract: POST /api/beds accepts exactly the three
     * location fields (server-set branch and AVAILABLE status — extra client
     * fields are ignored, never stored), every response is the strict DTO
     * allowlist with no JPA metadata and no legacy patient reference, and
     * create/transition/delete each own exactly one existing-format audit
     * event while OCCUPIED, unknown, and repeat targets are the shared 409
     * with no audit event.
     */
    @Test
    void bedLifecycleUsesTheNormalizedDtoAndRecordsExactlyOneAuditEventPerMutation() {
        Branch branch = createdBranch(suffix + "-bed");
        String token = adminTokenActingOn(branch);

        Map<String, Object> payload = new HashMap<>(bedCreatePayload("life"));
        payload.put("branchId", UUID.randomUUID().toString());
        payload.put("occupancyStatus", "OCCUPIED");
        payload.put("patientId", UUID.randomUUID().toString());
        ResponseEntity<Map<String, Object>> created = postJson("/api/beds", token, payload);
        assertTrue(created.getStatusCode().is2xxSuccessful(),
                "the normalized create must accept the three contract fields");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(BED_DTO_KEYS, body.keySet(),
                "the bed response must be exactly the DTO allowlist: no timestamps, version, or raw entity");
        assertEquals("WARD-MB-" + suffix, body.get("ward"));
        assertEquals("AVAILABLE", body.get("occupancyStatus"),
                "the server alone sets the initial AVAILABLE status — a client status value is ignored");
        assertEquals(branch.getId().toString(), String.valueOf(body.get("branchId")),
                "ownership derives from the acting context — a client branchId is ignored");
        assertFalse(body.containsKey("patientId"),
                "the legacy raw patient reference must never surface in the DTO");
        String bedId = String.valueOf(body.get("id"));
        assertEquals(1, auditEventsFor("Bed", bedId, "CREATE"),
                "the create must own exactly one CREATE audit event");

        ResponseEntity<Map<String, Object>> fetched = getJson("/api/beds/" + bedId, token);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertNotNull(fetched.getBody());
        assertEquals(BED_DTO_KEYS, fetched.getBody().keySet(), "get-by-id must return the same DTO allowlist");

        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/beds/" + UUID.randomUUID(), token).getStatusCode(),
                "an unknown bed id must answer the shared 404 contract");

        ResponseEntity<Map<String, Object>> toMaintenance = putJson("/api/beds/" + bedId + "/status", token,
                Map.of("status", "MAINTENANCE"));
        assertTrue(toMaintenance.getStatusCode().is2xxSuccessful(), "AVAILABLE -> MAINTENANCE must succeed");
        assertNotNull(toMaintenance.getBody());
        assertEquals("MAINTENANCE", toMaintenance.getBody().get("occupancyStatus"));
        assertEquals(1, auditEventsFor("Bed", bedId, "UPDATE"),
                "the successful transition must own exactly one UPDATE audit event");

        ResponseEntity<Map<String, Object>> backToAvailable = putJson("/api/beds/" + bedId + "/status", token,
                Map.of("status", "AVAILABLE"));
        assertTrue(backToAvailable.getStatusCode().is2xxSuccessful(),
                "MAINTENANCE -> AVAILABLE must be a legal reverse transition");
        assertEquals(2, auditEventsFor("Bed", bedId, "UPDATE"),
                "each successful transition must own exactly one UPDATE audit event");

        long updatesBefore = auditEventsFor("Bed", bedId, "UPDATE");
        for (String refusedTarget : List.of("OCCUPIED", "ARBITRARY-STATE")) {
            ResponseEntity<Map<String, Object>> refused = putJson("/api/beds/" + bedId + "/status", token,
                    Map.of("status", refusedTarget));
            assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(),
                    refusedTarget + " must be the shared 409 contract");
            assertNotNull(refused.getBody());
            assertEquals(API_ERROR_KEYS, refused.getBody().keySet(),
                    "the refused transition must use the shared safe contract");
        }
        assertEquals(HttpStatus.CONFLICT,
                putJson("/api/beds/" + bedId + "/status", token, Map.of("status", "AVAILABLE")).getStatusCode(),
                "a repeat transition is not a transition and must be refused");
        assertEquals(updatesBefore, auditEventsFor("Bed", bedId, "UPDATE"),
                "refused transitions must record no audit event");

        assertEquals(HttpStatus.OK, delete("/api/beds/" + bedId, token).getStatusCode(),
                "an unoccupied bed must be deletable");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/beds/" + bedId, token).getStatusCode());
        assertEquals(1, auditEventsFor("Bed", bedId, "DELETE"),
                "the delete must own exactly one DELETE audit event");
        assertEquals(1, auditEventsFor("Bed", bedId, "CREATE"),
                "the create event must be untouched by the later operations");
    }

    /**
     * Task 6 branch-isolation and duplicate contract: the same
     * (ward, room, bedNumber) key on two branches is two distinct rows while
     * a repeat inside one branch is the safe 409 with the controlled service
     * message; every list and detail read resolves only inside the acting
     * branch, and an unassigned legacy bed row stays invisible and
     * untouched. Failed duplicates record no audit event.
     */
    @Test
    void bedReadsAreBranchScopedAndDuplicateKeysConflictOnlyInsideOneBranch() {
        Branch branchA = createdBranch(suffix + "-ba2");
        Branch branchB = createdBranch(suffix + "-bb2");
        String tokenA = adminTokenActingOn(branchA);
        String tokenB = adminTokenActingOn(branchB);

        Map<String, Object> bedA = createBed(tokenA, "WARD-SHARED-" + suffix, "ROOM-SHARED", "BED-01");
        Map<String, Object> bedB = createBed(tokenB, "WARD-SHARED-" + suffix, "ROOM-SHARED", "BED-01");
        assertNotNull(bedA);
        assertNotNull(bedB);
        assertNotEquals(bedA.get("id"), bedB.get("id"),
                "the same natural key on two branches must be two distinct rows");
        assertEquals(branchB.getId().toString(), String.valueOf(bedB.get("branchId")),
                "each branch owns its own row");

        List<String> idsA = listOfIds(getList("/api/beds", tokenA));
        assertTrue(idsA.contains(String.valueOf(bedA.get("id"))), "branch A lists its own bed");
        assertFalse(idsA.contains(String.valueOf(bedB.get("id"))), "branch A never lists branch B's bed");
        assertFalse(listOfIds(getList("/api/beds", tokenB)).contains(String.valueOf(bedA.get("id"))),
                "the isolation is symmetric");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/beds/" + bedB.get("id"), tokenA).getStatusCode(),
                "a cross-branch bed id must be indistinguishable from a nonexistent one");

        long createsBefore = auditEventCount("Bed", "CREATE");
        ResponseEntity<Map<String, Object>> duplicate = postJson("/api/beds", tokenA, Map.of(
                "ward", "WARD-SHARED-" + suffix,
                "room", "ROOM-SHARED",
                "bedNumber", "BED-01"));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode(),
                "the same-branch natural-key repeat must be refused");
        assertNotNull(duplicate.getBody());
        assertEquals("Bed already exists in this branch with the same ward, room, and bed number",
                duplicate.getBody().get("message"),
                "the refusal must carry the controlled service message, never persistence internals");
        assertEquals(createsBefore, auditEventCount("Bed", "CREATE"),
                "the refused duplicate must record no audit event");

        Bed legacy = beds.save(new Bed(null, "WARD-LEGACY-" + suffix, "ROOM-LEGACY", "BED-LEGACY"));
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/beds/" + legacy.getId(), tokenA).getStatusCode(),
                "get must not disclose an unassigned legacy bed");
        assertFalse(listOfIds(getList("/api/beds", tokenA)).contains(legacy.getId().toString()),
                "the list must never disclose an unassigned legacy bed");
        Bed reloaded = beds.findById(legacy.getId()).orElseThrow();
        assertEquals("BED-LEGACY", reloaded.getBedNumber(), "the legacy row must stay untouched");
        assertNull(reloaded.getBranch(), "the legacy row must remain unassigned");
    }

    /**
     * Task 6 occupancy guard: an OCCUPIED bed is admission-owned (Task 7),
     * so every client status transition is refused and deletion is refused —
     * all with the shared 409 and no audit events — while the same bed once
     * unoccupied deletes normally.
     */
    @Test
    void occupiedBedsRefuseClientTransitionsAndDeletionWith409WithoutAuditEvents() {
        Branch branch = createdBranch(suffix + "-occ");
        String token = adminTokenActingOn(branch);
        Bed occupied = persistBedWithStatus(branch, "occ", "OCCUPIED");
        String occupiedId = occupied.getId().toString();

        long updatesBefore = auditEventCount("Bed", "UPDATE");
        long deletesBefore = auditEventCount("Bed", "DELETE");
        for (String target : List.of("AVAILABLE", "MAINTENANCE", "OUT_OF_SERVICE")) {
            assertEquals(HttpStatus.CONFLICT,
                    putJson("/api/beds/" + occupiedId + "/status", token, Map.of("status", target)).getStatusCode(),
                    "an OCCUPIED bed must refuse the " + target + " client transition");
        }
        assertEquals(HttpStatus.CONFLICT, delete("/api/beds/" + occupiedId, token).getStatusCode(),
                "an OCCUPIED bed must refuse deletion");
        assertTrue(beds.findById(occupied.getId()).isPresent(), "the refused delete must not remove the row");
        assertEquals(updatesBefore, auditEventCount("Bed", "UPDATE"),
                "refused occupancy-guard transitions must record no audit event");
        assertEquals(deletesBefore, auditEventCount("Bed", "DELETE"),
                "the refused delete must record no audit event");

        Bed released = beds.findById(occupied.getId()).orElseThrow();
        released.releaseByAdmission();
        beds.save(released);
        assertEquals(HttpStatus.OK, delete("/api/beds/" + occupiedId, token).getStatusCode(),
                "once unoccupied the same bed deletes normally");
    }

    /**
     * Task 6 concurrency proof: duplicate creates racing on the same natural
     * key inside one branch produce exactly one winner and only safe 409s
     * (service pre-check plus the DB unique-constraint backstop), and racing
     * status transitions on one bed produce exactly one winner via the
     * existing JPA optimistic-lock mechanism — each with exactly one audit
     * event and exactly one persisted outcome.
     */
    @Test
    void concurrentBedMutationsProduceExactlyOneWinnerAndOnlySafeConflicts() throws Exception {
        Branch branch = createdBranch(suffix + "-race");
        String token = adminTokenActingOn(branch);

        // Race A: N concurrent identical creates on the same natural key.
        int racers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<ResponseEntity<Map<String, Object>>>> creations = new java.util.ArrayList<>();
            for (int i = 0; i < racers; i++) {
                creations.add(pool.submit(() -> {
                    start.await();
                    return postJson("/api/beds", token, Map.of(
                            "ward", "WARD-RACE-" + suffix,
                            "room", "ROOM-RACE",
                            "bedNumber", "BED-RACE"));
                }));
            }
            start.countDown();
            long successes = 0;
            String winnerId = null;
            for (var future : creations) {
                ResponseEntity<Map<String, Object>> outcome = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome.getStatusCode().is2xxSuccessful()) {
                    successes++;
                    winnerId = String.valueOf(outcome.getBody().get("id"));
                } else {
                    assertEquals(HttpStatus.CONFLICT, outcome.getStatusCode(),
                            "a racing duplicate create must be the safe 409, never a 5xx");
                }
            }
            assertEquals(1, successes, "exactly one racing create may win");
            assertEquals(1, auditEventsFor("Bed", winnerId, "CREATE"),
                    "the winning create alone must own exactly one CREATE audit event");
            assertEquals(1L, beds.findByBranchId(branch.getId()).stream()
                            .filter(bed -> ("WARD-RACE-" + suffix).equals(bed.getWard())
                                    && "ROOM-RACE".equals(bed.getRoom())
                                    && "BED-RACE".equals(bed.getBedNumber()))
                            .count(),
                    "exactly one row may persist for the raced natural key");
            assertThrows(DataIntegrityViolationException.class,
                    () -> beds.save(new Bed(branch, "WARD-RACE-" + suffix, "ROOM-RACE", "BED-RACE")),
                    "the (branch, ward, room, bedNumber) DB unique constraint must backstop the pre-check");
        } finally {
            pool.shutdownNow();
        }

        // Race B: two concurrent transitions of one bed; exactly one wins.
        Map<String, Object> bedBody = createBed(token, "WARD-TR-" + suffix, "ROOM-TR", "BED-TR");
        String bedId = String.valueOf(bedBody.get("id"));
        ExecutorService transitionPool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            java.util.List<java.util.concurrent.Future<ResponseEntity<Map<String, Object>>>> transitions =
                    new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                transitions.add(transitionPool.submit(() -> {
                    bothReady.countDown();
                    bothReady.await();
                    return putJson("/api/beds/" + bedId + "/status", token, Map.of("status", "MAINTENANCE"));
                }));
            }
            int transitionWins = 0;
            for (var future : transitions) {
                ResponseEntity<Map<String, Object>> outcome = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome.getStatusCode().is2xxSuccessful()) {
                    transitionWins++;
                } else {
                    assertEquals(HttpStatus.CONFLICT, outcome.getStatusCode(),
                            "a losing racing transition must be the safe 409, never a 5xx");
                }
            }
            assertEquals(1, transitionWins, "exactly one racing transition may win");
            assertEquals("MAINTENANCE", getJson("/api/beds/" + bedId, token).getBody().get("occupancyStatus"),
                    "the bed must end in exactly the single winning state");
            assertEquals(1, auditEventsFor("Bed", bedId, "UPDATE"),
                    "exactly one UPDATE audit event must exist for the raced transition");
        } finally {
            transitionPool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 18b. Task 7: atomic admission bed assignment, transfer, and release.
    // ------------------------------------------------------------------

    /** Task 7 admission create body over a verified same-branch patient. */
    private Map<String, Object> admissionCreatePayload(String tag, String patientId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("patientId", patientId);
        payload.put("admittedAt", "2031-04-01T08:00:00");
        payload.put("reason", "synthetic admission " + suffix + " " + tag);
        return payload;
    }

    /** Reads one bed's occupancy through the Task 6 DTO contract. */
    private String bedOccupancy(String token, Object bedId) {
        ResponseEntity<Map<String, Object>> bed = getJson("/api/beds/" + bedId, token);
        assertEquals(HttpStatus.OK, bed.getStatusCode(), "the bed fixture must stay readable");
        assertNotNull(bed.getBody());
        return String.valueOf(bed.getBody().get("occupancyStatus"));
    }

    /**
     * Task 7 branch contract: every admission bed reference resolves inside
     * the acting branch — a cross-branch bed at creation, a cross-branch
     * patient at creation, a cross-branch admission id, and a cross-branch
     * target bed on the bed command all answer the generic 404,
     * indistinguishable from a nonexistent row — while the successful
     * command exposes only the allowlisted branchId plus the current-bed
     * summary and owns exactly one existing-format audit event; every
     * refusal records none and mutates nothing.
     */
    @Test
    void admissionBedCommandsResolveReferencesOnlyInsideTheActingBranch() {
        Branch branchA = createdBranch(suffix + "-adma");
        Branch branchB = createdBranch(suffix + "-admb");
        String tokenA = adminTokenActingOn(branchA);
        String tokenB = adminTokenActingOn(branchB);

        Map<String, Object> bedA1 = createBed(tokenA, "WARD-ADM-" + suffix, "ROOM-ADM-A1", "BED-A1");
        Map<String, Object> bedA2 = createBed(tokenA, "WARD-ADM-" + suffix, "ROOM-ADM-A2", "BED-A2");
        Map<String, Object> bedB1 = createBed(tokenB, "WARD-ADM-" + suffix, "ROOM-ADM-B1", "BED-B1");
        String patientA = createVerifiedPatientId(tokenA, "adma");
        String patientB = createVerifiedPatientId(tokenB, "admb");
        long admissionsBefore = admissions.count();

        // Cross-branch bed at creation: generic 404, nothing persists.
        Map<String, Object> crossBed = admissionCreatePayload("xbed", patientA);
        crossBed.put("bedId", String.valueOf(bedB1.get("id")));
        assertEquals(HttpStatus.NOT_FOUND, postJson("/api/admissions", tokenA, crossBed).getStatusCode(),
                "a cross-branch bed reference at creation must be the generic 404");
        assertEquals(admissionsBefore, admissions.count(), "the refused create must persist nothing");

        // Cross-branch patient at creation: generic 404, nothing persists.
        Map<String, Object> crossPatient = admissionCreatePayload("xpat", patientB);
        crossPatient.put("bedId", String.valueOf(bedA1.get("id")));
        assertEquals(HttpStatus.NOT_FOUND, postJson("/api/admissions", tokenA, crossPatient).getStatusCode(),
                "a cross-branch patient reference at creation must be the generic 404");
        assertEquals(admissionsBefore, admissions.count(), "the refused create must persist nothing");

        // Successful create with a branch-local bed.
        Map<String, Object> localCreate = admissionCreatePayload("local", patientA);
        localCreate.put("bedId", String.valueOf(bedA1.get("id")));
        ResponseEntity<Map<String, Object>> created = postJson("/api/admissions", tokenA, localCreate);
        assertTrue(created.getStatusCode().is2xxSuccessful(), "the branch-local create must succeed");
        Map<String, Object> admission = created.getBody();
        assertNotNull(admission);
        assertEquals(branchA.getId().toString(), String.valueOf(admission.get("branchId")),
                "branchId derives from the admission's verified patient inside the acting branch");
        assertEquals(bedA1.get("id"), ((Map<?, ?>) admission.get("currentBed")).get("bedId"),
                "the current-bed summary must name the occupied bed");
        String admissionId = String.valueOf(admission.get("id"));
        assertEquals("OCCUPIED", bedOccupancy(tokenA, bedA1.get("id")));
        assertEquals(1, auditEventsFor("Admission", admissionId, "CREATE"),
                "the create must own exactly one CREATE audit event");
        assertEquals(0, auditEventsFor("Admission", admissionId, "UPDATE"),
                "no command has touched the admission yet");

        // Cross-branch admission id on the bed command: generic 404.
        assertEquals(HttpStatus.NOT_FOUND,
                putJson("/api/admissions/" + admissionId + "/bed", tokenB, Map.of("bedId", String.valueOf(bedB1.get("id")))).getStatusCode(),
                "a cross-branch admission id must be the generic 404 on the bed command");

        // Cross-branch target bed on the bed command: generic 404 too.
        assertEquals(HttpStatus.NOT_FOUND,
                putJson("/api/admissions/" + admissionId + "/bed", tokenA, Map.of("bedId", String.valueOf(bedB1.get("id")))).getStatusCode(),
                "a cross-branch target bed must be the generic 404 on the bed command");
        assertEquals("OCCUPIED", bedOccupancy(tokenA, bedA1.get("id")),
                "the refused commands must not release the held bed");
        assertEquals(0, auditEventsFor("Admission", admissionId, "UPDATE"),
                "the refused commands must record no audit event");

        // Successful branch-local transfer: source released, target occupied, one event.
        ResponseEntity<Map<String, Object>> transferred = putJson("/api/admissions/" + admissionId + "/bed", tokenA,
                Map.of("bedId", String.valueOf(bedA2.get("id"))));
        assertTrue(transferred.getStatusCode().is2xxSuccessful(), "the branch-local transfer must succeed");
        assertEquals("AVAILABLE", bedOccupancy(tokenA, bedA1.get("id")), "the transfer must release the source bed");
        assertEquals("OCCUPIED", bedOccupancy(tokenA, bedA2.get("id")), "the transfer must occupy the target bed");
        assertEquals(bedA2.get("id"), ((Map<?, ?>) transferred.getBody().get("currentBed")).get("bedId"));
        assertEquals(1, auditEventsFor("Admission", admissionId, "UPDATE"),
                "the transfer must own exactly one UPDATE audit event");

        // Discharge releases the branch-local bed transactionally.
        ResponseEntity<Map<String, Object>> discharged = putJson("/api/admissions/" + admissionId + "/status", tokenA,
                Map.of("status", "DISCHARGED"));
        assertTrue(discharged.getStatusCode().is2xxSuccessful(), "the discharge must succeed");
        assertNull(discharged.getBody().get("currentBed"), "discharge must close the current-bed summary");
        assertEquals("AVAILABLE", bedOccupancy(tokenA, bedA2.get("id")), "discharge must release the held bed");
        assertEquals(2, auditEventsFor("Admission", admissionId, "UPDATE"),
                "exactly the transfer and discharge UPDATE events may exist");

        // The live-assignment table must be empty for this admission: no closed row lingers.
        assertEquals(0, bedAssignments.findAll().stream()
                        .filter(assignment -> assignment.getAdmissionId().toString().equals(admissionId))
                        .count(),
                "a released admission must leave no assignment row behind");
    }

    /**
     * Task 7 concurrency proof: two admissions racing through the real HTTP
     * bed command for the same AVAILABLE bed produce exactly one winner and
     * one safe 409 (the service AVAILABLE pre-check plus the assignment
     * table's (bedId) DB unique constraint and the bed row's optimistic
     * lock), the winner alone holds the bed and owns exactly one audit
     * event, the loser records none, and a direct repository write against
     * the occupied bed fails on the same constraint.
     */
    @Test
    void concurrentAdmissionsClaimingOneBedProduceExactlyOneWinner() throws Exception {
        Branch branch = createdBranch(suffix + "-admrace");
        String token = adminTokenActingOn(branch);
        String patientId = createVerifiedPatientId(token, "admrace");
        Map<String, Object> bed = createBed(token, "WARD-RACE2-" + suffix, "ROOM-RACE2", "BED-RACE2");
        String bedId = String.valueOf(bed.get("id"));
        String admission1 = String.valueOf(postJson("/api/admissions", token,
                admissionCreatePayload("race1", patientId)).getBody().get("id"));
        String admission2 = String.valueOf(postJson("/api/admissions", token,
                admissionCreatePayload("race2", patientId)).getBody().get("id"));

        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        String winner;
        try {
            CountDownLatch start = new CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<ResponseEntity<Map<String, Object>>>> claims =
                    new java.util.ArrayList<>();
            for (String admission : List.of(admission1, admission2)) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return putJson("/api/admissions/" + admission + "/bed", token, Map.of("bedId", bedId));
                }));
            }
            start.countDown();
            int wins = 0;
            winner = null;
            for (var future : claims) {
                ResponseEntity<Map<String, Object>> outcome = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome.getStatusCode().is2xxSuccessful()) {
                    wins++;
                    winner = String.valueOf(outcome.getBody().get("id"));
                } else {
                    assertEquals(HttpStatus.CONFLICT, outcome.getStatusCode(),
                            "a losing racing claim must be the safe 409, never a 5xx");
                    assertNotNull(outcome.getBody());
                    assertEquals(API_ERROR_KEYS, outcome.getBody().keySet(),
                            "the losing claim must carry the shared safe contract");
                }
            }
            assertEquals(1, wins, "exactly one racing claim may win");
            assertNotNull(winner);
        } finally {
            pool.shutdownNow();
        }
        String loser = winner.equals(admission1) ? admission2 : admission1;

        assertEquals("OCCUPIED", bedOccupancy(token, bedId), "the raced bed must end occupied by the winner");
        Map<String, Object> winnerDetail = getJson("/api/admissions/" + winner, token).getBody();
        assertNotNull(winnerDetail);
        assertEquals(bed.get("id"), ((Map<?, ?>) winnerDetail.get("currentBed")).get("bedId"),
                "the winner must expose the raced bed as its current bed");
        Map<String, Object> loserDetail = getJson("/api/admissions/" + loser, token).getBody();
        assertNotNull(loserDetail);
        assertNull(loserDetail.get("currentBed"), "the loser must still hold no bed");
        assertEquals(1, auditEventsFor("Admission", winner, "UPDATE"),
                "the winning claim alone must own exactly one UPDATE audit event");
        assertEquals(0, auditEventsFor("Admission", loser, "UPDATE"),
                "the losing claim must record no audit event");

        // The DB constraint is the backstop: a direct write against the occupied bed cannot persist.
        assertThrows(DataIntegrityViolationException.class,
                () -> bedAssignments.save(new AdmissionBedAssignment(
                        admissions.save(new Admission(patientId, "2031-04-02T08:00", null,
                                "synthetic constraint probe " + suffix, "ADMITTED")).getId(),
                        java.util.UUID.fromString(bedId))),
                "the (bedId) DB unique constraint must backstop the service pre-check");
        assertEquals(1, bedAssignments.findAll().stream()
                        .filter(assignment -> assignment.getBedId().toString().equals(bedId))
                        .count(),
                "exactly one live assignment may exist for the raced bed");
    }

    // ------------------------------------------------------------------
    // 18c. Task 7A: admission list/detail/commands are branch-scoped.
    // ------------------------------------------------------------------

    /**
     * Task 7A branch-isolation regression: a branch token cannot list,
     * detail, assign/transfer, discharge, or delete another branch's
     * admission — every denial is the generic 404, indistinguishable from a
     * nonexistent row — and after the whole denial sweep the other branch's
     * persisted admission row, live assignment, bed occupancy, and audit
     * success counts are unchanged. Ownership is server-stamped from the
     * acting branch (a stray client branchId is ignored), and a legacy
     * null-ownership admission stays invisible and untouchable while its
     * row remains untouched in the store.
     */
    @Test
    void admissionListDetailAndCommandsAreScopedToTheActingBranch() {
        Branch branchA = createdBranch(suffix + "-isoa");
        Branch branchB = createdBranch(suffix + "-isob");
        String tokenA = adminTokenActingOn(branchA);
        String tokenB = adminTokenActingOn(branchB);

        String patientA = createVerifiedPatientId(tokenA, "isoa");
        String patientB = createVerifiedPatientId(tokenB, "isob");
        Map<String, Object> bedA1 = createBed(tokenA, "WARD-ISO-" + suffix, "ROOM-ISO-A1", "BED-A1");
        Map<String, Object> bedB1 = createBed(tokenB, "WARD-ISO-" + suffix, "ROOM-ISO-B1", "BED-B1");
        Map<String, Object> bedB2 = createBed(tokenB, "WARD-ISO-" + suffix, "ROOM-ISO-B2", "BED-B2");

        // Branch B owns a bed-holding admission; branch A owns a plain one.
        Map<String, Object> createB = admissionCreatePayload("isob", patientB);
        createB.put("bedId", String.valueOf(bedB1.get("id")));
        ResponseEntity<Map<String, Object>> createdB = postJson("/api/admissions", tokenB, createB);
        assertTrue(createdB.getStatusCode().is2xxSuccessful(), "the branch-B fixture must create cleanly");
        assertNotNull(createdB.getBody());
        String admissionB = String.valueOf(createdB.getBody().get("id"));
        ResponseEntity<Map<String, Object>> createdA = postJson("/api/admissions", tokenA,
                admissionCreatePayload("isoa", patientA));
        assertTrue(createdA.getStatusCode().is2xxSuccessful(), "the branch-A fixture must create cleanly");
        assertNotNull(createdA.getBody());
        String admissionA = String.valueOf(createdA.getBody().get("id"));

        // A stray client branchId can never choose ownership: the server
        // stamps the acting branch, so the row created from A stays A's.
        Map<String, Object> hijack = admissionCreatePayload("hijack", patientA);
        hijack.put("branchId", branchB.getId().toString());
        ResponseEntity<Map<String, Object>> hijacked = postJson("/api/admissions", tokenA, hijack);
        assertTrue(hijacked.getStatusCode().is2xxSuccessful(), "the create itself must succeed");
        assertNotNull(hijacked.getBody());
        assertEquals(branchA.getId().toString(), String.valueOf(hijacked.getBody().get("branchId")),
                "ownership is server-stamped from the acting branch; a client branchId is ignored");
        assertFalse(listOfIds(getList("/api/admissions", tokenB))
                        .contains(String.valueOf(hijacked.getBody().get("id"))),
                "the branchId-hijack attempt must not surface in branch B's list either");

        // List: each branch sees exactly its own admissions, never the other's.
        List<String> idsA = listOfIds(getList("/api/admissions", tokenA));
        assertTrue(idsA.contains(admissionA), "branch A lists its own admission");
        assertFalse(idsA.contains(admissionB), "branch A must never list branch B's admission");
        List<String> idsB = listOfIds(getList("/api/admissions", tokenB));
        assertTrue(idsB.contains(admissionB), "branch B lists its own admission");
        assertFalse(idsB.contains(admissionA), "the isolation is symmetric");

        // Frozen baseline before the denial sweep.
        long createsB = auditEventsFor("Admission", admissionB, "CREATE");
        assertEquals(1, createsB, "the branch-B create must own exactly one CREATE audit event");
        long updatesB = auditEventsFor("Admission", admissionB, "UPDATE");
        long deletesB = auditEventsFor("Admission", admissionB, "DELETE");
        long assignmentsB = bedAssignments.findAll().stream()
                .filter(assignment -> assignment.getAdmissionId().toString().equals(admissionB))
                .count();
        assertEquals(1, assignmentsB, "the branch-B admission must hold exactly one live assignment");

        // Detail denial: branch A cannot read branch B's admission.
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/admissions/" + admissionB, tokenA).getStatusCode(),
                "a cross-branch detail read must be the generic 404");

        // Bed command denial: neither a branch-A nor a branch-B target bed.
        assertEquals(HttpStatus.NOT_FOUND,
                putJson("/api/admissions/" + admissionB + "/bed", tokenA,
                        Map.of("bedId", String.valueOf(bedA1.get("id")))).getStatusCode(),
                "a cross-branch bed assignment must be the generic 404");
        assertEquals(HttpStatus.NOT_FOUND,
                putJson("/api/admissions/" + admissionB + "/bed", tokenA,
                        Map.of("bedId", String.valueOf(bedB2.get("id")))).getStatusCode(),
                "a cross-branch transfer attempt must be the generic 404");

        // Discharge denial.
        assertEquals(HttpStatus.NOT_FOUND,
                putJson("/api/admissions/" + admissionB + "/status", tokenA,
                        Map.of("status", "DISCHARGED")).getStatusCode(),
                "a cross-branch discharge must be the generic 404");

        // Delete denial.
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/admissions/" + admissionB, tokenA).getStatusCode(),
                "a cross-branch delete must be the generic 404");

        // Nothing moved: the branch-B admission row, live assignment, bed
        // occupancy, and audit success counts are all unchanged.
        Admission persistedB = admissions.findById(UUID.fromString(admissionB)).orElseThrow();
        assertEquals("ADMITTED", persistedB.getStatus(), "the denied discharge must not change the status");
        assertNull(persistedB.getDischargedAt(), "the denied discharge must not stamp a discharge time");
        assertEquals(branchB.getId(), persistedB.getBranchId(), "ownership must stay with branch B");
        assertEquals(assignmentsB, bedAssignments.findAll().stream()
                        .filter(assignment -> assignment.getAdmissionId().toString().equals(admissionB))
                        .count(),
                "the denied commands must not create or close any assignment row");
        assertEquals("OCCUPIED", bedOccupancy(tokenB, bedB1.get("id")),
                "the denied bed command must not release or move the held bed");
        assertEquals("AVAILABLE", bedOccupancy(tokenA, bedA1.get("id")),
                "the denied bed command must not occupy the branch-A target");
        assertEquals(createsB, auditEventsFor("Admission", admissionB, "CREATE"),
                "the denied commands must not add a CREATE event");
        assertEquals(updatesB, auditEventsFor("Admission", admissionB, "UPDATE"),
                "the denied commands must record no UPDATE audit event");
        assertEquals(deletesB, auditEventsFor("Admission", admissionB, "DELETE"),
                "the denied commands must record no DELETE audit event");

        // Positive control: each branch keeps full authority over its own row.
        assertEquals(HttpStatus.OK, getJson("/api/admissions/" + admissionB, tokenB).getStatusCode(),
                "branch B still reads its own admission");
        assertEquals(HttpStatus.OK,
                putJson("/api/admissions/" + admissionA + "/status", tokenA,
                        Map.of("status", "DISCHARGED")).getStatusCode(),
                "branch A still discharges its own admission");

        // Legacy null-ownership admission: invisible and untouchable from
        // every branch, and the row itself stays untouched in the store.
        Admission legacy = admissions.save(new Admission(patientA, "2031-05-01T08:00", null,
                "synthetic legacy admission " + suffix, "ADMITTED"));
        assertFalse(listOfIds(getList("/api/admissions", tokenA)).contains(legacy.getId().toString()),
                "the list must never disclose a null-ownership legacy admission");
        assertFalse(listOfIds(getList("/api/admissions", tokenB)).contains(legacy.getId().toString()),
                "no other branch discloses it either");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/admissions/" + legacy.getId(), tokenA).getStatusCode(),
                "get must not disclose a null-ownership legacy admission");
        assertEquals(HttpStatus.NOT_FOUND,
                putJson("/api/admissions/" + legacy.getId() + "/status", tokenA,
                        Map.of("status", "DISCHARGED")).getStatusCode(),
                "discharge must not touch a null-ownership legacy admission");
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/admissions/" + legacy.getId(), tokenA).getStatusCode(),
                "delete must not remove a null-ownership legacy admission");
        Admission reloadedLegacy = admissions.findById(legacy.getId()).orElseThrow();
        assertEquals("ADMITTED", reloadedLegacy.getStatus(), "the legacy row must stay untouched");
        assertNull(reloadedLegacy.getDischargedAt(), "the legacy row must keep no discharge time");
        assertNull(reloadedLegacy.getBranchId(), "the legacy row must remain unowned");
    }

    // ------------------------------------------------------------------
    // 19. Retained Task 1: appointments accept overlapping times.
    // ------------------------------------------------------------------

    /**
     * Retained Task 1 characterization (Task 9 will change it): two
     * appointments for the same verified professional at exactly the same
     * timestamp both succeed today — no overlap rejection exists. Proven
     * through observable records and distinct ids.
     */
    @Test
    void appointmentsAcceptOverlappingTimesForTheSameProfessionalToday() {
        String token = login(ADMIN);
        String patientId = createVerifiedPatientId(token, "ovl");
        String professionalId = createVerifiedStaffId(token, "ovl");
        Map<String, Object> payload = appointmentPayload(patientId, professionalId);

        ResponseEntity<Map<String, Object>> firstResponse = postJson("/api/appointments", token, payload);
        ResponseEntity<Map<String, Object>> secondResponse = postJson("/api/appointments", token, payload);
        assertTrue(firstResponse.getStatusCode().is2xxSuccessful(), "the first overlapping create must succeed today");
        assertTrue(secondResponse.getStatusCode().is2xxSuccessful(),
                "the second identical-slot create must also succeed today — no overlap check exists");
        Map<String, Object> first = firstResponse.getBody();
        Map<String, Object> second = secondResponse.getBody();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.get("id"), second.get("id"),
                "the two overlapping appointments must be two distinct persisted records");
        assertEquals(professionalId, String.valueOf(first.get("professionalId")));
        assertEquals(professionalId, String.valueOf(second.get("professionalId")));
        assertNotNull(first.get("scheduledAt"));
        assertEquals(first.get("scheduledAt"), second.get("scheduledAt"),
                "both appointments must carry the exact same scheduled time");
    }

    // ------------------------------------------------------------------
    // 20. Retained Task 1: dashboard is whole-table and branchless.
    // ------------------------------------------------------------------

    /**
     * Retained Task 1 characterization (Task 10 will change it): the
     * dashboard exposes exactly the eleven flat numeric keys and newly
     * created synthetic rows move the whole-table totals. Increments are
     * asserted relative to a baseline read so the test stays independent of
     * suite ordering. Departments intentionally do not appear here in
     * Task 2.
     */
    @Test
    void dashboardIsAWholeTableBranchlessFlatCountMapToday() {
        String token = login(ADMIN);
        Map<String, Object> before = readDashboard(token);
        assertEquals(DASHBOARD_KEYS, before.keySet(),
                "today's dashboard is exactly the eleven flat keys with no branch/network keys");

        String patientId = createVerifiedPatientId(token, "dash");
        String professionalId = createVerifiedStaffId(token, "dash");
        postJson("/api/appointments", token, appointmentPayload(patientId, professionalId));

        Map<String, Object> after = readDashboard(token);
        assertEquals(DASHBOARD_KEYS, after.keySet(), "the dashboard must keep the same flat key set");
        assertEquals(count(before, "patients") + 1, count(after, "patients"),
                "the whole-table patient total must include the newly created synthetic row");
        assertEquals(count(before, "appointments") + 1, count(after, "appointments"),
                "the whole-table appointment total must include the newly created synthetic row");
    }

    // ------------------------------------------------------------------
    // 21. Retained Task 1: audit events lack organizational context.
    // ------------------------------------------------------------------

    /**
     * Retained Task 1 characterization (Task 11 will change it): a
     * successful department mutation emits one event carrying only the
     * actor/action/resource/details/time contract — no assignment, branch,
     * organization, role, or correlation fields. The event is located by
     * its resource id, not by ordering; the create runs through the new
     * Task 2 contract (verified branch) while the event shape pin remains
     * today's.
     */
    @Test
    void auditEventsCarryNoOrganizationalContextToday() {
        String token = login(ADMIN);
        String branchId = String.valueOf(createBranch(token, suffix + "-auditshape").get("id"));
        Map<String, Object> created = postJson("/api/departments", token, departmentPayload("auditshape", branchId)).getBody();
        assertNotNull(created);
        String resourceId = String.valueOf(created.get("id"));

        ResponseEntity<List<Map<String, Object>>> auditResponse = getList("/api/audit", token);
        assertEquals(HttpStatus.OK, auditResponse.getStatusCode());
        List<Map<String, Object>> events = auditResponse.getBody();
        assertNotNull(events, "the audit list must carry a body");
        Map<String, Object> event = events.stream()
                .filter(candidate -> "Department".equals(candidate.get("resourceType"))
                        && resourceId.equals(candidate.get("resourceId")))
                .findFirst()
                .orElse(null);
        assertNotNull(event, "the department create must have produced one observable audit event");
        assertEquals(Set.of("id", "createdAt", "updatedAt", "version", "actor", "action",
                        "resourceType", "resourceId", "details", "occurredAt"),
                event.keySet(),
                "today's audit event carries only actor/action/resource/details/time plus persistence metadata");
        assertEquals(ADMIN, event.get("actor"));
        assertEquals("CREATE", event.get("action"));
        assertEquals("Department", event.get("resourceType"));
        assertNotNull(event.get("occurredAt"), "the event must carry its occurred-at time");
        assertTrue(event.keySet().stream().noneMatch(key -> {
            String lowered = key.toLowerCase();
            return lowered.contains("assignment") || lowered.contains("branch")
                    || lowered.contains("organization") || lowered.contains("department")
                    || lowered.contains("role") || lowered.contains("correlation");
        }), "no organizational-context field may exist on today's audit event");
    }

    // ------------------------------------------------------------------
    // 22. Task 4: patients, staff, and appointments are branch-owned.
    // ------------------------------------------------------------------

    /** Issues a replacement ADMIN token acting exactly on one synthetic branch through the real context contract. */
    private String adminTokenActingOn(Branch branch) {
        UUID adminAssignmentId = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(
                        accounts.findByUsername(ADMIN).orElseThrow().getId())
                .stream().filter(a -> a.getRole() == Role.ADMIN).findFirst().orElseThrow().getId();
        ResponseEntity<Map<String, Object>> switched = postJson("/api/auth/context", login(ADMIN), Map.of(
                "assignmentId", adminAssignmentId.toString(),
                "branchId", branch.getId().toString()));
        assertEquals(HttpStatus.OK, switched.getStatusCode(),
                "the organization-scope ADMIN must act on any active branch of its organization");
        assertNotNull(switched.getBody());
        return String.valueOf(switched.getBody().get("accessToken"));
    }

    /** Creates a branch through the public contract and loads the persisted entity. */
    private Branch createdBranch(String code) {
        String id = String.valueOf(createBranch(login(ADMIN), code).get("id"));
        return branches.findById(UUID.fromString(id))
                .orElseThrow(() -> new AssertionError("the created branch must be persisted: " + code));
    }

    /** The id set of a JSON-array response body, asserting the shared 200 shape first. */
    private List<String> listOfIds(ResponseEntity<List<Map<String, Object>>> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> body = response.getBody();
        assertNotNull(body, "the list response must carry a body");
        return body.stream().map(row -> String.valueOf(row.get("id"))).toList();
    }

    /**
     * Task 4 two-branch proof: patient, staff, and appointment creation
     * derives ownership from the acting context on each branch (the
     * response branchId equals the acting branch, and the create request
     * carries no branch input), while every list, search, and detail read
     * resolves only inside the acting branch — a cross-branch id answers
     * the shared 404, indistinguishable from a nonexistent row.
     */
    @Test
    void workflowRecordsAreOwnedByTheActingBranchAndEveryWorkflowReadIsBranchScoped() {
        Branch branchA = createdBranch(suffix + "-ta");
        Branch branchB = createdBranch(suffix + "-tb");
        String tokenA = adminTokenActingOn(branchA);
        String tokenB = adminTokenActingOn(branchB);

        // Creation derives ownership from the acting context on both branches.
        Map<String, Object> patientA = postJson("/api/patients", tokenA, patientCreatePayload("ta")).getBody();
        Map<String, Object> staffA = postJson("/api/staff", tokenA, staffCreatePayload("ta")).getBody();
        Map<String, Object> patientB = postJson("/api/patients", tokenB, patientCreatePayload("tb")).getBody();
        Map<String, Object> staffB = postJson("/api/staff", tokenB, staffCreatePayload("tb")).getBody();
        assertNotNull(patientA);
        assertNotNull(staffA);
        assertNotNull(patientB);
        assertNotNull(staffB);
        assertEquals(branchA.getId().toString(), String.valueOf(patientA.get("branchId")),
                "the patient must be owned by the acting branch derived from the context");
        assertEquals(branchB.getId().toString(), String.valueOf(patientB.get("branchId")),
                "the same request shape must bind to the other acting branch, proving context derivation");
        assertEquals(branchA.getId().toString(), String.valueOf(staffA.get("branchId")),
                "the staff record must be owned by the acting branch");
        assertEquals(branchB.getId().toString(), String.valueOf(staffB.get("branchId")),
                "the other-branch staff record must bind to its own acting branch");

        Map<String, Object> appointmentA = postJson("/api/appointments", tokenA,
                appointmentPayload(String.valueOf(patientA.get("id")), String.valueOf(staffA.get("id")))).getBody();
        Map<String, Object> appointmentB = postJson("/api/appointments", tokenB,
                appointmentPayload(String.valueOf(patientB.get("id")), String.valueOf(staffB.get("id")))).getBody();
        assertNotNull(appointmentA);
        assertNotNull(appointmentB);
        assertEquals(branchA.getId().toString(), String.valueOf(appointmentA.get("branchId")),
                "the appointment must be owned by the acting branch");

        // Cross-branch detail reads are the shared 404 — indistinguishable from nonexistent.
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/patients/" + patientB.get("id"), tokenA).getStatusCode(),
                "branch A must not see branch B's patient");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/staff/" + staffB.get("id"), tokenA).getStatusCode(),
                "branch A must not see branch B's professional");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/appointments/" + appointmentB.get("id"), tokenA).getStatusCode(),
                "branch A must not see branch B's appointment");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/patients/" + patientA.get("id"), tokenB).getStatusCode(),
                "the isolation is symmetric");

        // Lists resolve only inside the acting branch.
        List<String> patientIdsA = listOfIds(getList("/api/patients", tokenA));
        assertTrue(patientIdsA.contains(String.valueOf(patientA.get("id"))), "branch A lists its own patient");
        assertFalse(patientIdsA.contains(String.valueOf(patientB.get("id"))), "branch A never lists branch B's patient");
        List<String> staffIdsA = listOfIds(getList("/api/staff", tokenA));
        assertTrue(staffIdsA.contains(String.valueOf(staffA.get("id"))), "branch A lists its own professional");
        assertFalse(staffIdsA.contains(String.valueOf(staffB.get("id"))), "branch A never lists branch B's professional");
        List<String> appointmentIdsA = listOfIds(getList("/api/appointments", tokenA));
        assertTrue(appointmentIdsA.contains(String.valueOf(appointmentA.get("id"))), "branch A lists its own appointment");
        assertFalse(appointmentIdsA.contains(String.valueOf(appointmentB.get("id"))),
                "branch A never lists branch B's appointment");

        // Search resolves only inside the acting branch (the per-instance suffix matches exactly this method's rows).
        assertEquals(List.of(String.valueOf(patientA.get("id"))), listOfIds(getList("/api/patients?q=" + suffix, tokenA)),
                "the branch-scoped search must match only branch A's row");
        assertEquals(List.of(String.valueOf(patientB.get("id"))), listOfIds(getList("/api/patients?q=" + suffix, tokenB)),
                "the same query from branch B must match only branch B's row");
    }

    /**
     * Task 4 reference validation and audit contract: an appointment
     * referencing a row of another branch is refused with the same 404 as a
     * nonexistent reference (no existence leak), failed writes record no
     * audit event while the same-branch success records exactly one, and
     * the MRN uniqueness stays global — a cross-branch duplicate is still
     * the shared 409.
     */
    @Test
    void crossBranchReferencesAreRefusedWithoutAuditWhileGlobalUniquenessStaysGlobal() {
        Branch branchA = createdBranch(suffix + "-ra");
        Branch branchB = createdBranch(suffix + "-rb");
        String tokenA = adminTokenActingOn(branchA);
        String tokenB = adminTokenActingOn(branchB);

        Map<String, Object> patientA = postJson("/api/patients", tokenA, patientCreatePayload("ra")).getBody();
        Map<String, Object> staffA = postJson("/api/staff", tokenA, staffCreatePayload("ra")).getBody();
        Map<String, Object> patientB = postJson("/api/patients", tokenB, patientCreatePayload("rb")).getBody();
        Map<String, Object> staffB = postJson("/api/staff", tokenB, staffCreatePayload("rb")).getBody();
        assertNotNull(patientA);
        assertNotNull(staffA);
        assertNotNull(patientB);
        assertNotNull(staffB);

        long appointmentCreatesBefore = auditEventCount("Appointment", "CREATE");
        assertEquals(HttpStatus.NOT_FOUND, postJson("/api/appointments", tokenA,
                        appointmentPayload(String.valueOf(patientB.get("id")), String.valueOf(staffA.get("id")))).getStatusCode(),
                "a cross-branch patient reference must be indistinguishable from a nonexistent one");
        assertEquals(HttpStatus.NOT_FOUND, postJson("/api/appointments", tokenA,
                        appointmentPayload(String.valueOf(patientA.get("id")), String.valueOf(staffB.get("id")))).getStatusCode(),
                "a cross-branch professional reference must be refused like an unknown one");
        assertEquals(appointmentCreatesBefore, auditEventCount("Appointment", "CREATE"),
                "failed cross-branch writes must record no audit event");

        assertTrue(postJson("/api/appointments", tokenA,
                        appointmentPayload(String.valueOf(patientA.get("id")), String.valueOf(staffA.get("id"))))
                        .getStatusCode().is2xxSuccessful(),
                "the same-branch reference pair must succeed");
        assertEquals(appointmentCreatesBefore + 1, auditEventCount("Appointment", "CREATE"),
                "exactly one Appointment CREATE audit event must follow the successful write");

        Map<String, Object> duplicateMrn = new HashMap<>(patientCreatePayload("rb-dup"));
        duplicateMrn.put("medicalRecordNumber", patientA.get("medicalRecordNumber"));
        assertEquals(HttpStatus.CONFLICT, postJson("/api/patients", tokenB, duplicateMrn).getStatusCode(),
                "MRN uniqueness stays global in Phase 3 — a cross-branch duplicate is still the shared conflict");
        assertEquals(appointmentCreatesBefore + 1, auditEventCount("Appointment", "CREATE"),
                "the refused duplicate patient write must record no audit event");
    }

    /**
     * Task 4 legacy seam: workflow rows persisted before branch ownership
     * (null branch) are invisible and untouchable through every
     * branch-scoped endpoint while staying untouched in the store —
     * mirroring the Task 2 unassigned-department contract.
     */
    @Test
    void unassignedLegacyWorkflowRowsStayInvisibleAndUntouchableThroughBranchScopedEndpoints() {
        String token = adminTokenActingOn(branchByCode(TEST_DEFAULT_BRANCH_CODE));

        Patient legacyPatient = patients.save(new Patient(null, "MRN-MB-" + suffix + "-legacy",
                "Legacy Unassigned Patient " + suffix, null, null, null, null, null, null));
        StaffMember legacyStaff = staffMembers.save(new StaffMember(null, "EMP-MB-" + suffix + "-legacy",
                "Legacy Unassigned Professional " + suffix, "cardiology", "LIC-MB-" + suffix + "-legacy",
                "internal medicine"));
        Appointment legacyAppointment = appointments.save(new Appointment(null,
                legacyPatient.getId().toString(), legacyStaff.getId().toString(),
                "2031-09-09T09:00", "consultation", "scheduled"));

        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/patients/" + legacyPatient.getId(), token).getStatusCode(),
                "get must not disclose an unassigned legacy patient");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/staff/" + legacyStaff.getId(), token).getStatusCode(),
                "get must not disclose an unassigned legacy professional");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/appointments/" + legacyAppointment.getId(), token).getStatusCode(),
                "get must not disclose an unassigned legacy appointment");
        assertFalse(listOfIds(getList("/api/patients", token)).contains(legacyPatient.getId().toString()),
                "the list must never disclose an unassigned legacy patient");
        assertFalse(listOfIds(getList("/api/staff", token)).contains(legacyStaff.getId().toString()),
                "the list must never disclose an unassigned legacy professional");
        assertFalse(listOfIds(getList("/api/appointments", token)).contains(legacyAppointment.getId().toString()),
                "the list must never disclose an unassigned legacy appointment");

        assertEquals(legacyPatient.getMedicalRecordNumber(),
                patients.findById(legacyPatient.getId()).orElseThrow().getMedicalRecordNumber(),
                "the legacy patient row must stay untouched");
        assertNull(patients.findById(legacyPatient.getId()).orElseThrow().getBranch(),
                "the legacy patient row must remain unassigned");
        assertNull(staffMembers.findById(legacyStaff.getId()).orElseThrow().getBranch(),
                "the legacy professional row must remain unassigned");
        assertNull(appointments.findById(legacyAppointment.getId()).orElseThrow().getBranch(),
                "the legacy appointment row must remain unassigned");
    }

    // ------------------------------------------------------------------
    // HTTP and synthetic-data helpers
    // ------------------------------------------------------------------

    /** Loads or creates the single synthetic test organization (no create endpoint exists). */
    private void ensureOrganization() {
        organizations.findByCode(TEST_ORG_CODE).orElseGet(
                () -> organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Synthetic MultiBranch Hospital")));
    }

    /**
     * Wipes the hierarchy tables in FK-safe order — since docs/plan3.md
     * Task 4 the workflow rows are branch-owned and reference branches, so
     * they leave first alongside the acting assignments that also reference
     * branches and organizations — and then fully restores the shared
     * foundation so every later login keeps working.
     */
    private void wipeHierarchy() {
        appointments.deleteAll();
        beds.deleteAll();
        staffMembers.deleteAll();
        patients.deleteAll();
        assignments.deleteAll();
        departments.deleteAll();
        branches.deleteAll();
        organizations.deleteAll();
    }

    /** Creates a branch through the public API and returns its DTO body. */
    private Map<String, Object> createBranch(String token, String code) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/branches", token, Map.of(
                "code", code,
                "name", "Synthetic Branch " + code,
                "locationLabel", "9 " + code + " Way"));
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), "the synthetic branch " + code + " must be creatable");
        assertNotNull(created.getBody());
        return created.getBody();
    }

    /** Loads a branch created by this test through the real repository. */
    private Branch branchByCode(String code) {
        var org = organizations.findByCode(TEST_ORG_CODE).orElseThrow();
        return branches.findByOrganizationIdAndCode(org.getId(), code)
                .orElseThrow(() -> new AssertionError("the test must have created branch " + code));
    }

    private long auditEventCount(String resourceType, String action) {
        List<Map<String, Object>> events = getList("/api/audit", login(ADMIN)).getBody();
        assertNotNull(events, "the audit list must carry a body");
        return events.stream()
                .filter(event -> resourceType.equals(event.get("resourceType")) && action.equals(event.get("action")))
                .count();
    }

    private long auditEventsFor(String resourceType, String resourceId, String action) {
        List<Map<String, Object>> events = getList("/api/audit", login(ADMIN)).getBody();
        assertNotNull(events, "the audit list must carry a body");
        return events.stream()
                .filter(event -> resourceType.equals(event.get("resourceType"))
                        && resourceId.equals(event.get("resourceId"))
                        && action.equals(event.get("action")))
                .count();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> branchList(Object raw) {
        assertInstanceOf(List.class, raw, "the active branches must be a JSON array");
        return (List<Map<String, Object>>) raw;
    }

    /** Synthetic department create body over a verified branch reference. */
    private Map<String, Object> departmentPayload(String code, String branchId) {
        return Map.of(
                "branchId", branchId,
                "code", code,
                "name", "Synthetic Department " + code,
                "specialty", "general",
                "location", "5 " + code + " Ward");
    }

    private String login(String username) {
        ResponseEntity<Map<String, Object>> response = postJson("/api/auth/login", null, Map.of(
                "username", username,
                "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the synthetic account must log in");
        assertNotNull(response.getBody());
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private ResponseEntity<Map<String, Object>> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), LIST);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, String token, Map<String, Object> payload) {
        return exchangeJson(path, HttpMethod.POST, token, payload);
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, String token, Map<String, Object> payload) {
        return exchangeJson(path, HttpMethod.PUT, token, payload);
    }

    private ResponseEntity<Map<String, Object>> exchangeJson(String path, HttpMethod method, String token,
                                                             Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), MAP);
    }

    private ResponseEntity<String> rawExchange(HttpMethod method, String path, String token,
                                               Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (payload != null) {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private Map<String, Object> readDashboard(String token) {
        ResponseEntity<Map<String, Object>> response = getJson("/api/dashboard", token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the dashboard response must carry a body");
        return body;
    }

    private long count(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }

    /** Synthetic CreatePatientRequest body, unique per tag within this run. */
    private Map<String, Object> patientCreatePayload(String tag) {
        return Map.ofEntries(
                Map.entry("medicalRecordNumber", "MRN-MB-" + suffix + "-" + tag),
                Map.entry("fullName", "Synthetic MultiBranch Patient " + suffix + " " + tag),
                Map.entry("dateOfBirth", "1991-02-03"),
                Map.entry("sex", "unspecified"),
                Map.entry("phone", "+15550003333"),
                Map.entry("email", "mb-" + tag + "-" + suffix + "@synthetic.test"),
                Map.entry("nationalId", "NID-MB-" + suffix + "-" + tag),
                Map.entry("address", "3 MultiBranch Avenue"));
    }

    /** Synthetic staff create body over obviously fake directory values. */
    private Map<String, Object> staffCreatePayload(String tag) {
        return Map.of(
                "employeeCode", "EMP-MB-" + suffix + "-" + tag,
                "fullName", "Dr. Synthetic MultiBranch " + suffix + " " + tag,
                "profession", "cardiology",
                "licenseNumber", "LIC-MB-" + suffix + "-" + tag,
                "department", "internal medicine");
    }

    /** Synthetic raw bed body: free-form status plus a nonexistent patient reference. */
    private Map<String, Object> bedPayload(String tag) {
        return Map.of(
                "ward", "WARD-MB-" + suffix,
                "room", "ROOM-MB-" + suffix + "-" + tag,
                "bedNumber", "BED-MB-" + suffix + "-" + tag,
                "occupancyStatus", "ARBITRARY-STATE-" + tag,
                "patientId", UUID.randomUUID().toString());
    }

    /** Synthetic CreateAppointmentRequest body over verified references. */
    private Map<String, Object> appointmentPayload(String patientId, String professionalId) {
        return Map.of(
                "patientId", patientId,
                "professionalId", professionalId,
                "scheduledAt", "2036-07-08T10:30:00",
                "type", "consultation",
                "status", "scheduled");
    }

    private String createVerifiedPatientId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/patients", token, patientCreatePayload(tag));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "synthetic patient creation must succeed");
        assertNotNull(created.getBody());
        return String.valueOf(created.getBody().get("id"));
    }

    private String createVerifiedStaffId(String token, String tag) {
        ResponseEntity<Map<String, Object>> created = postJson("/api/staff", token, staffCreatePayload(tag));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "synthetic staff creation must succeed");
        assertNotNull(created.getBody());
        return String.valueOf(created.getBody().get("id"));
    }
}

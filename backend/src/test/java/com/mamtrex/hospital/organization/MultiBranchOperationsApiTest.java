package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
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
 * indifference, raw bed weakness, appointment overlap, whole-table
 * dashboard, context-free audit events) keep pinning today's unrelated
 * weaknesses for their later tasks. Department rows with no branch are the
 * deliberate legacy transition seam: this suite proves the normalized
 * contract never exposes or mutates them.</p>
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
    // 18. Retained Task 1: beds accept free-form branchless occupancy.
    // ------------------------------------------------------------------

    /**
     * Retained Task 1 characterization (Task 6 will change it): POST
     * /api/beds accepts an arbitrary nonblank occupancy status and a
     * nonexistent patientId, returns the raw entity without branchId, and
     * the CREATE audit evidence stays observable without ordering
     * dependence. Beds are intentionally NOT branch scoped in Task 2.
     */
    @Test
    void bedsAcceptFreeFormBranchlessOccupancyWithAnUnverifiedPatientReferenceToday() {
        String token = login(ADMIN);
        Map<String, Object> payload = bedPayload("ghost");
        ResponseEntity<Map<String, Object>> created = postJson("/api/beds", token, payload);
        assertTrue(created.getStatusCode().is2xxSuccessful(),
                "today's raw bed CRUD must accept the free-form request");
        Map<String, Object> body = created.getBody();
        assertNotNull(body);
        assertEquals(Set.of("id", "createdAt", "updatedAt", "version", "ward", "room", "bedNumber",
                        "occupancyStatus", "patientId"),
                body.keySet(),
                "today's bed response is the raw entity with free-form string occupancy fields");
        assertEquals(payload.get("occupancyStatus"), body.get("occupancyStatus"),
                "any nonblank occupancy status string must be accepted today");
        assertEquals(payload.get("patientId"), body.get("patientId"),
                "an unverified nonexistent patientId must be stored as-is today");
        assertFalse(body.containsKey("branchId"), "no branch ownership exists on today's bed record");

        ResponseEntity<List<Map<String, Object>>> list = getList("/api/beds", token);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        List<Map<String, Object>> rows = list.getBody();
        assertNotNull(rows, "the bed list must carry a body");
        assertTrue(rows.stream().anyMatch(row -> body.get("id").equals(row.get("id"))),
                "the created bed must appear in the global unscoped list");

        ResponseEntity<List<Map<String, Object>>> auditResponse = getList("/api/audit", token);
        assertEquals(HttpStatus.OK, auditResponse.getStatusCode());
        List<Map<String, Object>> events = auditResponse.getBody();
        assertNotNull(events, "the audit list must carry a body");
        assertTrue(events.stream().anyMatch(event ->
                        "CREATE".equals(event.get("action"))
                                && "Bed".equals(event.get("resourceType"))
                                && String.valueOf(body.get("id")).equals(event.get("resourceId"))),
                "the bed create must leave observable CREATE audit evidence for its resource id");
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
    // HTTP and synthetic-data helpers
    // ------------------------------------------------------------------

    /** Loads or creates the single synthetic test organization (no create endpoint exists). */
    private void ensureOrganization() {
        organizations.findByCode(TEST_ORG_CODE).orElseGet(
                () -> organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Synthetic MultiBranch Hospital")));
    }

    /**
     * Wipes the hierarchy tables in FK-safe order — acting assignments first,
     * because they reference branches and organizations — and then fully
     * restores the shared foundation so every later login keeps working.
     */
    private void wipeHierarchy() {
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
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
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

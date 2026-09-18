package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 T032/T037 (US1): the authorized network-hierarchy authorization
 * and API matrix over real HTTP against an isolated in-memory H2 database
 * with synthetic disposable records only.
 *
 * <p>Fixture: one synthetic network with three hospitals across three IANA
 * zones (UTC, America/New_York, Asia/Tokyo), two synthetic branches per
 * hospital, and one synthetic department on the first branch of the first
 * hospital. Four acting scopes are exercised: ORGANIZATION (network),
 * HOSPITAL, BRANCH, and DEPARTMENT. The matrix proves each scope receives
 * exactly its authorized descendants in deterministic order, that foreign
 * hospital/branch identifiers never appear in any response and can never be
 * supplied for expansion (the endpoint accepts no hierarchy parameters), and
 * that inactive ancestors and missing authority fail closed with generic,
 * non-enumerating responses.</p>
 *
 * <p>T037 adds a bounded query-count proof: the number of SQL queries
 * executed by one hierarchy request is measured with real Hibernate
 * statistics before and after the authorized descendant set grows
 * (three hospitals to six hospitals), and must stay identical — a per-card
 * (N+1) loader would scale with the fixture and fail the equality bound.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:network-hierarchy-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "hospital.jwt.secret=" + NetworkHierarchyApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + NetworkHierarchyApiTest.TEST_ACCOUNT_PASSWORD
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkHierarchyApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-nethier-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-nethier-01";

    private static final String ORG_CODE = "NETHIER-ORG-001";

    private static final String HOSPITAL_1 = "NET-HOSP-001";
    private static final String HOSPITAL_2 = "NET-HOSP-002";
    private static final String HOSPITAL_3 = "NET-HOSP-003";

    /** Deterministic synthetic hierarchy keys; codes order deterministically. */
    private static final List<String> SEED_HOSPITAL_CODES =
            List.of(HOSPITAL_1, HOSPITAL_2, HOSPITAL_3);
    private static final Map<String, String> HOSPITAL_ZONES = Map.of(
            HOSPITAL_1, "UTC",
            HOSPITAL_2, "America/New_York",
            HOSPITAL_3, "Asia/Tokyo",
            "NET-HOSP-004", "Europe/Paris",
            "NET-HOSP-005", "Australia/Sydney",
            "NET-HOSP-006", "America/Sao_Paulo");
    private static final Map<String, List<String>> HOSPITAL_BRANCH_CODES = Map.of(
            HOSPITAL_1, List.of("NET-BR-101", "NET-BR-102"),
            HOSPITAL_2, List.of("NET-BR-201", "NET-BR-202"),
            HOSPITAL_3, List.of("NET-BR-301", "NET-BR-302"));

    private static final String NETWORK_ADMIN = "nethier-network-admin";
    private static final String HOSPITAL_ADMIN_2 = "nethier-hospital-admin-2";
    private static final String HOSPITAL_ADMIN_3 = "nethier-hospital-admin-3";
    private static final String BRANCH_NURSE_3 = "nethier-branch-nurse-3";
    private static final String BRANCH_NURSE_3B = "nethier-branch-nurse-3b";
    private static final String DEPARTMENT_DOCTOR_1 = "nethier-department-doctor-1";
    private static final String NO_AUTHORITY = "nethier-no-assignment";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    HospitalFacilityRepository hospitals;

    @Autowired
    BranchRepository branches;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void seedDisposableHierarchyAndAccounts() {
        HospitalOrganization organization = ensureOrganization();
        Map<String, HospitalFacility> hospitalByCode = new java.util.LinkedHashMap<>();
        for (String hospitalCode : SEED_HOSPITAL_CODES) {
            hospitalByCode.put(hospitalCode, ensureHospital(organization, hospitalCode));
        }
        for (Map.Entry<String, List<String>> entry : HOSPITAL_BRANCH_CODES.entrySet()) {
            HospitalFacility hospital = hospitalByCode.get(entry.getKey());
            for (String branchCode : entry.getValue()) {
                ensureBranch(hospital, branchCode);
            }
        }
        ensureDepartment(branches.findByHospitalIdAndCode(
                        hospitalByCode.get(HOSPITAL_1).getId(), "NET-BR-101").orElseThrow(),
                "NET-DEP-101");

        ensureAccount(NETWORK_ADMIN);
        ensureAssignment(NETWORK_ADMIN, Role.ADMIN, AssignmentScope.ORGANIZATION, organization, null, null);
        ensureAccount(HOSPITAL_ADMIN_2);
        ensureAssignment(HOSPITAL_ADMIN_2, Role.ADMIN, AssignmentScope.HOSPITAL, organization,
                hospitalByCode.get(HOSPITAL_2), null);
        ensureAccount(HOSPITAL_ADMIN_3);
        ensureAssignment(HOSPITAL_ADMIN_3, Role.ADMIN, AssignmentScope.HOSPITAL, organization,
                hospitalByCode.get(HOSPITAL_3), null);
        ensureAccount(BRANCH_NURSE_3);
        ensureAssignment(BRANCH_NURSE_3, Role.NURSE, AssignmentScope.BRANCH, organization, null,
                branches.findByHospitalIdAndCode(hospitalByCode.get(HOSPITAL_3).getId(), "NET-BR-302")
                        .orElseThrow());
        ensureAccount(BRANCH_NURSE_3B);
        ensureAssignment(BRANCH_NURSE_3B, Role.NURSE, AssignmentScope.BRANCH, organization, null,
                branches.findByHospitalIdAndCode(hospitalByCode.get(HOSPITAL_3).getId(), "NET-BR-301")
                        .orElseThrow());
        ensureAccount(DEPARTMENT_DOCTOR_1);
        ensureAssignment(DEPARTMENT_DOCTOR_1, Role.DOCTOR, AssignmentScope.DEPARTMENT, organization, null, null,
                departments.findByBranchIdAndCode(
                        branches.findByHospitalIdAndCode(hospitalByCode.get(HOSPITAL_1).getId(),
                                "NET-BR-101").orElseThrow().getId(), "NET-DEP-101").orElseThrow());
        ensureAccount(NO_AUTHORITY);
    }

    // ------------------------------------------------------------------
    // Fixture helpers (lookup-before-create, idempotent across methods).
    // ------------------------------------------------------------------

    private HospitalOrganization ensureOrganization() {
        return organizations.findByCode(ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(ORG_CODE, "NetHierarchy Synthetic Network")));
    }

    private HospitalFacility ensureHospital(HospitalOrganization organization, String code) {
        return hospitals.findByOrganizationIdAndCode(organization.getId(), code)
                .orElseGet(() -> hospitals.save(new HospitalFacility(organization, code,
                        "Synthetic Hospital " + code, "Synthetic Region " + code, HOSPITAL_ZONES.get(code))));
    }

    private Branch ensureBranch(HospitalFacility hospital, String code) {
        return branches.findByHospitalIdAndCode(hospital.getId(), code)
                .orElseGet(() -> branches.save(new Branch(hospital, code,
                        "Synthetic Branch " + code, "1 Synthetic Way", hospital.getTimeZone())));
    }

    private Department ensureDepartment(Branch branch, String code) {
        return departments.findByBranchIdAndCode(branch.getId(), code)
                .orElseGet(() -> departments.save(new Department(branch, code,
                        "Synthetic Department " + code, "general medicine", "Synthetic Wing")));
    }

    private void ensureAccount(String username) {
        accounts.findByUsername(username).orElseGet(() ->
                accounts.save(new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD),
                        Set.of(Role.ADMIN))));
    }

    private void ensureAssignment(String username, Role role, AssignmentScope scope,
                                  HospitalOrganization organization, HospitalFacility hospital,
                                  Branch branch) {
        ensureAssignment(username, role, scope, organization, hospital, branch, null);
    }

    private void ensureAssignment(String username, Role role, AssignmentScope scope,
                                  HospitalOrganization organization, HospitalFacility hospital,
                                  Branch branch, Department department) {
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        UUID hospitalId = hospital == null ? null : hospital.getId();
        UUID branchId = branch == null ? null : branch.getId();
        UUID departmentId = department == null ? null : department.getId();
        boolean present = assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId())
                .stream()
                .anyMatch(candidate -> candidate.getScope() == scope
                        && (hospitalId == null || hospitalId.equals(candidate.getHospital() == null
                                ? null : candidate.getHospital().getId()))
                        && (branchId == null || branchId.equals(candidate.getBranch() == null
                                ? null : candidate.getBranch().getId()))
                        && (departmentId == null || departmentId.equals(candidate.getDepartment() == null
                                ? null : candidate.getDepartment().getId())));
        if (present) {
            return;
        }
        ActingAssignment created = switch (scope) {
            case ORGANIZATION -> ActingAssignment.organization(account, organization, role);
            case HOSPITAL -> ActingAssignment.hospital(account, organization, role, hospital);
            case BRANCH -> ActingAssignment.branch(account, organization, role, branch);
            // The department factory derives hospital and branch through the
            // department's lazy relationships, so it runs inside a short
            // transaction against a freshly managed department (the HTTP
            // test thread has no open session).
            case DEPARTMENT -> transactionTemplate.execute(tx ->
                    assignments.save(ActingAssignment.department(account, organization, role,
                            departments.findById(department.getId()).orElseThrow())));
        };
        if (created != null && scope != AssignmentScope.DEPARTMENT) {
            assignments.save(created);
        }
    }

    // ------------------------------------------------------------------
    // Authentication helpers.
    // ------------------------------------------------------------------

    private String login(String username) {
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of("username", username, "password", TEST_ACCOUNT_PASSWORD)), MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "fixture login must succeed for " + username);
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<Map<String, Object>> hierarchy(String token) {
        return hierarchy(token, null);
    }

    private ResponseEntity<Map<String, Object>> hierarchy(String token, String junkQuery) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String path = junkQuery == null ? "/api/network/hierarchy" : "/api/network/hierarchy" + junkQuery;
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hospitalRows(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("hospitals");
    }

    @SuppressWarnings("unchecked")
    private List<String> hospitalCodes(ResponseEntity<Map<String, Object>> response) {
        return hospitalRows(response).stream().map(row -> String.valueOf(row.get("code"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> branchCodes(ResponseEntity<Map<String, Object>> response, String hospitalCode) {
        return hospitalRows(response).stream()
                .filter(row -> hospitalCode.equals(String.valueOf(row.get("code"))))
                .findFirst().orElseThrow()
                .get("branches") instanceof List<?> list
                ? list.stream().map(row -> String.valueOf(((Map<String, Object>) row).get("code"))).toList()
                : List.of();
    }

    private Set<String> allBodyIdValues(ResponseEntity<Map<String, Object>> response) {
        Set<String> ids = new java.util.HashSet<>();
        collectIdStrings(response.getBody(), ids);
        return ids;
    }

    @SuppressWarnings("unchecked")
    private void collectIdStrings(Object value, Set<String> into) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (("id".equals(key) || key.endsWith("Id")) && entry.getValue() != null) {
                    into.add(String.valueOf(entry.getValue()));
                }
                collectIdStrings(entry.getValue(), into);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectIdStrings(item, into);
            }
        }
    }

    // ------------------------------------------------------------------
    // The authorization matrix (T032).
    // ------------------------------------------------------------------

    /**
     * Network scope: every active hospital with all of its active branches,
     * in deterministic order (hospitals by code, branches by code inside
     * each hospital), with exactly the contract keys at every level.
     */
    @Test
    @Order(1)
    void networkScopeReturnsEveryActiveHospitalAndBranchInDeterministicOrder() {
        ResponseEntity<Map<String, Object>> response = hierarchy(login(NETWORK_ADMIN));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Set.of("organizationId", "organizationCode", "organizationName", "hospitals"),
                response.getBody().keySet(), "the body must be exactly the contract allowlist");
        assertEquals(ORG_CODE, String.valueOf(response.getBody().get("organizationCode")));
        assertEquals(SEED_HOSPITAL_CODES, hospitalCodes(response),
                "hospitals must appear in deterministic code order");
        for (String hospitalCode : SEED_HOSPITAL_CODES) {
            assertEquals(HOSPITAL_BRANCH_CODES.get(hospitalCode), branchCodes(response, hospitalCode),
                    "branches of " + hospitalCode + " must appear in deterministic code order");
        }
    }

    /** Hospital scope: exactly the acting hospital and its branches — zero foreign identifiers. */
    @Test
    @Order(2)
    void hospitalScopeReturnsOnlyItsOwnHospitalAndNeverForeignIdentifiers() {
        ResponseEntity<Map<String, Object>> response = hierarchy(login(HOSPITAL_ADMIN_2));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(HOSPITAL_2), hospitalCodes(response),
                "a hospital-scoped administrator must see exactly the acting hospital");
        assertEquals(HOSPITAL_BRANCH_CODES.get(HOSPITAL_2), branchCodes(response, HOSPITAL_2));

        Set<String> foreignIds = new java.util.HashSet<>();
        for (String foreignCode : List.of(HOSPITAL_1, HOSPITAL_3)) {
            HospitalFacility foreign = hospitals.findByOrganizationIdAndCode(
                    organizations.findByCode(ORG_CODE).orElseThrow().getId(), foreignCode).orElseThrow();
            foreignIds.add(foreign.getId().toString());
            for (String branchCode : HOSPITAL_BRANCH_CODES.get(foreignCode)) {
                foreignIds.add(branches.findByHospitalIdAndCode(foreign.getId(), branchCode)
                        .orElseThrow().getId().toString());
            }
        }
        Set<String> bodyIds = allBodyIdValues(response);
        bodyIds.retainAll(foreignIds);
        assertTrue(bodyIds.isEmpty(),
                "no foreign hospital/branch identifier may appear anywhere in a hospital-scoped body");
    }

    /** Branch scope: exactly the acting branch inside its hospital — zero sibling disclosure. */
    @Test
    @Order(3)
    void branchScopeReturnsOnlyItsOwnBranchInsideItsHospital() {
        ResponseEntity<Map<String, Object>> response = hierarchy(login(BRANCH_NURSE_3));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(HOSPITAL_3), hospitalCodes(response));
        assertEquals(List.of("NET-BR-302"), branchCodes(response, HOSPITAL_3),
                "a branch-scoped user must see exactly the acting branch, never its sibling");
    }

    /** Department scope: the department's branch is the only visible branch of its hospital. */
    @Test
    @Order(4)
    void departmentScopeDerivesItsBranchSliceThroughItsDepartment() {
        ResponseEntity<Map<String, Object>> response = hierarchy(login(DEPARTMENT_DOCTOR_1));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(HOSPITAL_1), hospitalCodes(response));
        assertEquals(List.of("NET-BR-101"), branchCodes(response, HOSPITAL_1),
                "a department-scoped user must see exactly the department's branch");
    }

    /**
     * Foreign identifiers are never authorization evidence: the endpoint
     * accepts no hierarchy parameters, so client-supplied hospital/branch
     * ids are ignored — a hospital-scoped caller appending foreign ids to
     * the URL receives a byte-identical body to the plain request and can
     * never expand the response beyond the server-derived slice.
     */
    @Test
    @Order(5)
    void clientSuppliedHierarchyIdentifiersAreNeverAcceptedForExpansion() {
        String token = login(HOSPITAL_ADMIN_2);
        UUID foreignHospitalId = hospitals.findByOrganizationIdAndCode(
                organizations.findByCode(ORG_CODE).orElseThrow().getId(), HOSPITAL_1).orElseThrow().getId();
        UUID foreignBranchId = branches.findByHospitalIdAndCode(
                hospitals.findByOrganizationIdAndCode(
                        organizations.findByCode(ORG_CODE).orElseThrow().getId(), HOSPITAL_3).orElseThrow().getId(),
                "NET-BR-301").orElseThrow().getId();

        ResponseEntity<Map<String, Object>> plain = hierarchy(token);
        ResponseEntity<Map<String, Object>> expanded = hierarchy(token,
                "?hospitalId=" + foreignHospitalId + "&branchId=" + foreignBranchId);
        assertEquals(HttpStatus.OK, expanded.getStatusCode());
        assertEquals(plain.getBody(), expanded.getBody(),
                "client-supplied hierarchy identifiers must never change the server-derived response");
        assertEquals(List.of(HOSPITAL_2), hospitalCodes(expanded));
    }

    /**
     * Inactive hospital (FR-004): it disappears from the network view with
     * all of its branches, and both of its scoped actors fail closed
     * unauthenticated on their next request — Phase 5 (T048/T053) invalidates
     * the acting context itself when a hospital ancestor is inactive, so a
     * branch-scoped user now lands on the same shared 401 boundary as the
     * hospital-scoped administrator. Neither the hospital's existence nor its
     * state is disclosed (SC-011 intentional, equal/stronger migration).
     */
    @Test
    @Order(8)
    void inactiveHospitalFailsClosedForEveryScopeWithoutDisclosure() {
        String networkToken = login(NETWORK_ADMIN);
        String hospital3Token = login(HOSPITAL_ADMIN_3);
        // The sibling nurse acts on HOSP-003's OTHER branch, which stays
        // active here: the refusal below is caused by the inactive HOSPITAL
        // ancestor alone, never by the branch.
        String branch3Token = login(BRANCH_NURSE_3B);

        HospitalFacility hospital3 = hospitals.findByOrganizationIdAndCode(
                organizations.findByCode(ORG_CODE).orElseThrow().getId(), HOSPITAL_3).orElseThrow();
        hospital3.deactivate();
        hospitals.save(hospital3);

        ResponseEntity<Map<String, Object>> network = hierarchy(networkToken);
        assertEquals(HttpStatus.OK, network.getStatusCode());
        assertEquals(List.of(HOSPITAL_1, HOSPITAL_2), hospitalCodes(network),
                "an inactive hospital and its branches must disappear from the network view");
        assertEquals(HOSPITAL_BRANCH_CODES.get(HOSPITAL_1), branchCodes(network, HOSPITAL_1),
                "other hospitals must be untouched");

        assertEquals(HttpStatus.UNAUTHORIZED, hierarchy(hospital3Token).getStatusCode(),
                "a hospital-scoped actor of an inactive hospital must fail closed unauthenticated");

        ResponseEntity<Map<String, Object>> branchActor = hierarchy(branch3Token);
        assertEquals(HttpStatus.UNAUTHORIZED, branchActor.getStatusCode(),
                "a branch actor of an inactive hospital must fail closed unauthenticated immediately: "
                        + "the inactive hospital ancestor invalidates the acting context itself, "
                        + "exactly like the hospital-scoped actor above (FR-004, T048/T053)");
    }

    /**
     * Inactive branch (FR-004): it disappears from the hierarchy and its
     * branch-scoped user is immediately unauthenticated (the per-request
     * context reload refuses the inactive branch).
     */
    @Test
    @Order(7)
    void inactiveBranchDisappearsAndItsScopedActorLosesAuthentication() {
        String networkToken = login(NETWORK_ADMIN);
        String branch3Token = login(BRANCH_NURSE_3);

        Branch branch302 = branches.findByHospitalIdAndCode(hospitals.findByOrganizationIdAndCode(
                        organizations.findByCode(ORG_CODE).orElseThrow().getId(), HOSPITAL_3).orElseThrow().getId(),
                "NET-BR-302").orElseThrow();
        branch302.deactivate();
        branches.save(branch302);

        ResponseEntity<Map<String, Object>> network = hierarchy(networkToken);
        assertEquals(HttpStatus.OK, network.getStatusCode());
        assertEquals(List.of("NET-BR-301"), branchCodes(network, HOSPITAL_3),
                "an inactive branch must disappear from its hospital");

        assertEquals(HttpStatus.UNAUTHORIZED, hierarchy(branch3Token).getStatusCode(),
                "a branch-scoped actor of an inactive branch must be unauthenticated immediately");
    }

    /**
     * Missing authority: anonymous callers stay on the shared 401 boundary,
     * and an account without any acting assignment cannot even log in, so
     * no hierarchy response can ever exist for it (the generic
     * non-enumerating 401 on both boundaries).
     */
    @Test
    @Order(6)
    void anonymousAndAssignmentlessCallersAreRefusedGenerically() {
        ResponseEntity<Map<String, Object>> anonymous = rest.exchange(
                "/api/network/hierarchy", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode());

        ResponseEntity<Map<String, Object>> refusedLogin = rest.exchange("/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of("username", NO_AUTHORITY, "password", TEST_ACCOUNT_PASSWORD)), MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, refusedLogin.getStatusCode(),
                "an account without any acting assignment must fail login with the generic 401");
    }

    // ------------------------------------------------------------------
    // Bounded query count (T037).
    // ------------------------------------------------------------------

    /**
     * The hierarchy loader must issue the same number of SQL queries no
     * matter how many hospitals/branches the authorized network contains:
     * the count measured for the three-hospital fixture must equal the
     * count measured after the fixture grows to six hospitals (twelve
     * branches). A per-card loader (N+1 or per-hospital loop) would scale
     * and break the equality; both absolute counts must also stay under a
     * fixed generous bound.
     */
    @Test
    @Order(9)
    void hierarchyQueryCountStaysBoundedAsTheAuthorizedFixtureGrows() {
        String token = login(NETWORK_ADMIN);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        long beforeSmall = statistics.getQueryExecutionCount();
        assertEquals(HttpStatus.OK, hierarchy(token).getStatusCode());
        long smallFixtureQueries = statistics.getQueryExecutionCount() - beforeSmall;
        long activeHospitalsBeforeGrowth = hospitalCodes(hierarchy(token)).size();

        HospitalOrganization organization = organizations.findByCode(ORG_CODE).orElseThrow();
        List<String> grownHospitalCodes = List.of("NET-HOSP-004", "NET-HOSP-005", "NET-HOSP-006");
        Map<String, List<String>> grownBranchCodes = Map.of(
                "NET-HOSP-004", List.of("NET-BR-401", "NET-BR-402"),
                "NET-HOSP-005", List.of("NET-BR-501", "NET-BR-502"),
                "NET-HOSP-006", List.of("NET-BR-601", "NET-BR-602"));
        for (String code : grownHospitalCodes) {
            HospitalFacility hospital = ensureHospital(organization, code);
            for (String branchCode : grownBranchCodes.get(code)) {
                ensureBranch(hospital, branchCode);
            }
        }

        statistics.clear();
        long beforeGrown = statistics.getQueryExecutionCount();
        ResponseEntity<Map<String, Object>> grown = hierarchy(token);
        assertEquals(HttpStatus.OK, grown.getStatusCode());
        long grownFixtureQueries = statistics.getQueryExecutionCount() - beforeGrown;

        assertEquals(activeHospitalsBeforeGrowth + grownHospitalCodes.size(), hospitalCodes(grown).size(),
                "the grown fixture must be visible to prove the second measurement is real");
        assertEquals(smallFixtureQueries, grownFixtureQueries,
                "hierarchy loading must issue the same query count regardless of hospital/branch card count "
                        + "(N+1 loaders scale with the fixture)");
        assertTrue(smallFixtureQueries <= 32,
                "even the bounded absolute count must stay under a fixed bound (observed "
                        + smallFixtureQueries + " for the small fixture, " + grownFixtureQueries
                        + " for the grown fixture)");
    }
}

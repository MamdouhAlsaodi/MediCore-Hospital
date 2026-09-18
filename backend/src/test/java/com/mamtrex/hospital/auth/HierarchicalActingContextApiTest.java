package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 US2 (T046/T047, T049/T050/T052 pins): the hierarchical
 * acting-context API matrix over real HTTP against an isolated in-memory H2
 * database with synthetic disposable records only.
 *
 * <p>The matrix proves that login, session, and context-switch responses
 * carry the server-derived hospital chain for all four assignment shapes
 * (ORGANIZATION, HOSPITAL, BRANCH, DEPARTMENT), that a context switch may
 * select only among server-issued hospital/branch targets of the acting
 * assignment (client ids can never widen authority), that foreign,
 * mismatched, unknown, missing, and inactive targets all receive one
 * generic non-enumerating 403, and that per-request structural equality
 * includes the hospital id so stale or tampered contexts fail closed.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:hierarchical-acting-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + HierarchicalActingContextApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + HierarchicalActingContextApiTest.TEST_ACCOUNT_PASSWORD
})
class HierarchicalActingContextApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-hiact-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-hiact-01";

    private static final String ORG_CODE = "HIACT-ORG-001";
    private static final String HOSPITAL_1 = "HIACT-HOSP-001";
    private static final String HOSPITAL_2 = "HIACT-HOSP-002";

    private static final String NETWORK_ADMIN = "hiact-network-admin";
    private static final String HOSPITAL_ADMIN_1 = "hiact-hospital-admin-1";
    private static final String BRANCH_NURSE_201 = "hiact-branch-nurse-201";
    private static final String DEPARTMENT_DOCTOR_101 = "hiact-department-doctor-101";

    private static final Set<String> SESSION_KEYS = Set.of(
            "accessToken", "tokenType", "username", "roles", "assignments", "actingContext");
    /** Strict allowlist including the Phase 5 hospital fields (T051). */
    private static final Set<String> ASSIGNMENT_VIEW_KEYS = Set.of(
            "id", "role", "scope", "organizationId", "organizationLabel",
            "hospitalId", "hospitalLabel",
            "branchId", "branchLabel", "departmentId", "departmentLabel", "enabled");
    private static final Set<String> ACTING_CONTEXT_KEYS = Set.of(
            "username", "assignmentId", "role", "scope",
            "organizationId", "hospitalId", "branchId", "departmentId");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<Map<String, Object>>() {};

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
    HospitalFacilityRepository hospitals;

    @Autowired
    BranchRepository branches;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    TransactionTemplate transactionTemplate;

    /** Destructive branch-state manipulation only (no public deactivation endpoint exists). */
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Unique synthetic suffix per test instance keeps destructive fixtures disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void seedDisposableHierarchyAndAccounts() {
        HospitalOrganization organization = ensureOrganization();
        HospitalFacility hospital1 = ensureHospital(organization, HOSPITAL_1, "UTC");
        HospitalFacility hospital2 = ensureHospital(organization, HOSPITAL_2, "America/New_York");
        Branch br101 = ensureBranch(hospital1, "HIACT-BR-101");
        ensureBranch(hospital1, "HIACT-BR-102");
        Branch br201 = ensureBranch(hospital2, "HIACT-BR-201");
        ensureBranch(hospital2, "HIACT-BR-202");
        Department dep101 = ensureDepartment(br101, "HIACT-DEP-101");

        ensureAccount(NETWORK_ADMIN);
        ensureAssignment(NETWORK_ADMIN, Role.ADMIN, AssignmentScope.ORGANIZATION, organization, null, null, null);
        ensureAccount(HOSPITAL_ADMIN_1);
        ensureAssignment(HOSPITAL_ADMIN_1, Role.ADMIN, AssignmentScope.HOSPITAL, organization, hospital1, null, null);
        ensureAccount(BRANCH_NURSE_201);
        ensureAssignment(BRANCH_NURSE_201, Role.NURSE, AssignmentScope.BRANCH, organization, null, br201, null);
        ensureAccount(DEPARTMENT_DOCTOR_101);
        ensureAssignment(DEPARTMENT_DOCTOR_101, Role.DOCTOR, AssignmentScope.DEPARTMENT, organization, null, null,
                dep101);
    }

    // ------------------------------------------------------------------
    // T046 — login/session/context responses carry the hospital chain.
    // ------------------------------------------------------------------

    @Test
    void organizationLoginBindsTheDeterministicFirstActiveHospitalBranchChainAndTheStrictAllowlist() {
        Map<String, Object> body = loginBody(NETWORK_ADMIN);
        assertEquals(SESSION_KEYS, body.keySet(), "the session response is exactly the six-key allowlist");
        Map<String, Object> context = castMap(body.get("actingContext"));
        assertEquals(ACTING_CONTEXT_KEYS, context.keySet(),
                "the acting context carries exactly the structural chain including the hospital id");
        assertEquals("ORGANIZATION", context.get("scope"));
        assertEquals(String.valueOf(organization().getId()), context.get("organizationId"));
        assertEquals(String.valueOf(hospital(HOSPITAL_1).getId()), context.get("hospitalId"),
                "the network login resolves the deterministic first active branch's own facility");
        assertEquals(String.valueOf(branch("HIACT-BR-101").getId()), context.get("branchId"));
        assertNull(context.get("departmentId"));
        assertEquals(List.of("ADMIN"), body.get("roles"), "exactly one acting role");

        List<Map<String, Object>> assignmentViews = assignmentList(body);
        assertEquals(1, assignmentViews.size());
        Map<String, Object> view = assignmentViews.get(0);
        assertEquals(ASSIGNMENT_VIEW_KEYS, view.keySet(),
                "assignment views expose exactly the allowlist including hospital id/label");
        assertEquals("ORGANIZATION", view.get("scope"));
        assertNull(view.get("hospitalId"), "an ORGANIZATION assignment fixes no hospital");
        assertNull(view.get("hospitalLabel"));
        assertNull(view.get("branchId"));
        assertNull(view.get("departmentId"));
        assertEquals(organization().getName(), view.get("organizationLabel"));
        assertEquals(Boolean.TRUE, view.get("enabled"));
        assertFalse(body.containsKey("passwordHash"));
    }

    @Test
    void hospitalLoginBindsItsFacilityAndItsDeterministicFirstActiveBranch() {
        Map<String, Object> body = loginBody(HOSPITAL_ADMIN_1);
        Map<String, Object> context = castMap(body.get("actingContext"));
        assertEquals("HOSPITAL", context.get("scope"));
        assertEquals(String.valueOf(hospital(HOSPITAL_1).getId()), context.get("hospitalId"),
                "the hospital login binds the assignment's own facility");
        assertEquals(String.valueOf(branch("HIACT-BR-101").getId()), context.get("branchId"),
                "the branch is the deterministic first active branch of that facility");

        Map<String, Object> view = assignmentList(body).get(0);
        assertEquals(String.valueOf(hospital(HOSPITAL_1).getId()), view.get("hospitalId"));
        assertEquals(hospital(HOSPITAL_1).getName(), view.get("hospitalLabel"),
                "the assignment view exposes the server-owned hospital label");

        Set<String> ids = allBodyIdValues(body);
        assertFalse(ids.contains(hospital(HOSPITAL_2).getId().toString()),
                "a hospital-scoped session never carries another hospital's identifiers");
        assertFalse(ids.contains(branch("HIACT-BR-201").getId().toString()),
                "a hospital-scoped session never carries another hospital's branches");
    }

    @Test
    void branchAndDepartmentLoginsDeriveTheHospitalThroughTheServerChain() {
        Map<String, Object> nurse = loginBody(BRANCH_NURSE_201);
        Map<String, Object> nurseContext = castMap(nurse.get("actingContext"));
        assertEquals("BRANCH", nurseContext.get("scope"));
        assertEquals(String.valueOf(hospital(HOSPITAL_2).getId()), nurseContext.get("hospitalId"),
                "the branch login derives the hospital from the fixed branch itself");
        assertEquals(String.valueOf(branch("HIACT-BR-201").getId()), nurseContext.get("branchId"));
        assertEquals(String.valueOf(hospital(HOSPITAL_2).getId()), assignmentList(nurse).get(0).get("hospitalId"));

        Map<String, Object> doctor = loginBody(DEPARTMENT_DOCTOR_101);
        Map<String, Object> doctorContext = castMap(doctor.get("actingContext"));
        assertEquals("DEPARTMENT", doctorContext.get("scope"));
        assertEquals(String.valueOf(hospital(HOSPITAL_1).getId()), doctorContext.get("hospitalId"),
                "the department login derives the hospital through its branch");
        assertEquals(String.valueOf(branch("HIACT-BR-101").getId()), doctorContext.get("branchId"));
        assertNotNull(doctorContext.get("departmentId"));
        Map<String, Object> doctorView = assignmentList(doctor).get(0);
        assertEquals(String.valueOf(hospital(HOSPITAL_1).getId()), doctorView.get("hospitalId"));
        assertNull(doctorView.get("branchId"), "a DEPARTMENT assignment fixes no branch column");
        assertEquals(hospital(HOSPITAL_1).getName(), doctorView.get("hospitalLabel"));
    }

    @Test
    void allFourAssignmentShapesResolveAtLoginInDeterministicScopeOrder() {
        HospitalOrganization org = dedicatedOrganization();
        HospitalFacility hospitalA = dedicatedHospital(org, "HIACT-HOSP-D1-" + suffix, "UTC");
        HospitalFacility hospitalB = dedicatedHospital(org, "HIACT-HOSP-D2-" + suffix, "Asia/Tokyo");
        Branch branchA = dedicatedBranch(hospitalA, "HIACT-BR-D1-" + suffix);
        dedicatedBranch(hospitalB, "HIACT-BR-D2-" + suffix);
        Department deptA = dedicatedDepartment(branchA, "HIACT-DEP-D1-" + suffix);

        UserAccount switcher = accounts.save(new UserAccount("hiact-shapes-" + suffix,
                encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        assignments.save(ActingAssignment.organization(switcher, org, Role.NURSE));
        assignments.save(ActingAssignment.hospital(switcher, org, Role.NURSE, hospitalB));
        assignments.save(ActingAssignment.branch(switcher, org, Role.NURSE, branchA));
        transactionTemplate.execute(tx -> assignments.save(
                ActingAssignment.department(accounts.findById(switcher.getId()).orElseThrow(), org, Role.NURSE,
                        departments.findById(deptA.getId()).orElseThrow())));

        Map<String, Object> body = loginBody(switcher.getUsername());
        assertEquals(List.of("NURSE"), body.get("roles"), "login selects one role");
        List<String> scopes = assignmentList(body).stream().map(v -> String.valueOf(v.get("scope"))).toList();
        assertEquals(List.of("BRANCH", "DEPARTMENT", "HOSPITAL", "ORGANIZATION"), scopes,
                "all four assignment shapes are listed in the deterministic (role, scope, id) order");
        Map<String, Object> context = castMap(body.get("actingContext"));
        assertEquals("BRANCH", context.get("scope"),
                "login deterministically selects the first assignment in (role, scope, id) order");
        assertEquals(String.valueOf(hospitalA.getId()), context.get("hospitalId"),
                "the branch scope derives a concrete hospital");
        assertEquals(String.valueOf(branchA.getId()), context.get("branchId"));
    }

    @Test
    void organizationSwitchSelectsAnAllowedHospitalBranchPairAndBindsAllThreeIds() {
        UUID assignment = assignmentIdFor(NETWORK_ADMIN);
        String token = login(NETWORK_ADMIN);

        Map<String, Object> switched = switchBody(token, assignment, hospital(HOSPITAL_2).getId(),
                branch("HIACT-BR-202").getId());
        assertEquals(List.of("ADMIN"), switched.get("roles"),
                "the replacement session carries exactly the target role");
        Map<String, Object> context = castMap(switched.get("actingContext"));
        assertEquals(String.valueOf(organization().getId()), context.get("organizationId"));
        assertEquals(String.valueOf(hospital(HOSPITAL_2).getId()), context.get("hospitalId"),
                "the switch binds the selected hospital id");
        assertEquals(String.valueOf(branch("HIACT-BR-202").getId()), context.get("branchId"),
                "the switch binds the selected branch id");
        assertNotEquals(token, switched.get("accessToken"), "the switch issues a replacement token");

        Map<String, Object> back = switchBody(token, assignment, hospital(HOSPITAL_1).getId(),
                branch("HIACT-BR-102").getId());
        assertEquals(String.valueOf(branch("HIACT-BR-102").getId()),
                castMap(back.get("actingContext")).get("branchId"));
    }

    @Test
    void hospitalScopeSwitchSelectsABranchInsideItsOwnFixedFacility() {
        UUID assignment = assignmentIdFor(HOSPITAL_ADMIN_1);
        String token = login(HOSPITAL_ADMIN_1);
        Map<String, Object> switched = switchBody(token, assignment, hospital(HOSPITAL_1).getId(),
                branch("HIACT-BR-102").getId());
        Map<String, Object> context = castMap(switched.get("actingContext"));
        assertEquals(String.valueOf(hospital(HOSPITAL_1).getId()), context.get("hospitalId"),
                "a hospital-scope switch stays inside the fixed facility");
        assertEquals(String.valueOf(branch("HIACT-BR-102").getId()), context.get("branchId"));
    }

    // ------------------------------------------------------------------
    // T047 — foreign/mismatched/unknown/missing/inactive chains refuse
    // generically without enumeration.
    // ------------------------------------------------------------------

    @Test
    void switchRefusesMismatchedForeignAndUnknownChainsIndistinguishably() {
        String token = login(NETWORK_ADMIN);
        UUID assignment = assignmentIdFor(NETWORK_ADMIN);
        UUID hospital1 = hospital(HOSPITAL_1).getId();
        UUID hospital2 = hospital(HOSPITAL_2).getId();
        UUID br101 = branch("HIACT-BR-101").getId();
        UUID br202 = branch("HIACT-BR-202").getId();

        ResponseEntity<Map<String, Object>> mismatched =
                switchResponse(token, assignment, hospital1, br202,
                        "a branch of another hospital is refused even though both ids exist");
        ResponseEntity<Map<String, Object>> mismatchedSwapped =
                switchResponse(token, assignment, hospital2, br101,
                        "the swapped mismatch is refused identically");
        ResponseEntity<Map<String, Object>> unknownHospital =
                switchResponse(token, assignment, UUID.randomUUID(), br101, "an unknown hospital is refused");
        ResponseEntity<Map<String, Object>> unknownBranch =
                switchResponse(token, assignment, hospital1, UUID.randomUUID(), "an unknown branch is refused");

        for (ResponseEntity<Map<String, Object>> refusal : List.of(mismatchedSwapped, unknownHospital, unknownBranch)) {
            assertEquals(HttpStatus.FORBIDDEN, refusal.getStatusCode());
            assertEquals(withoutTimestamp(mismatched.getBody()), withoutTimestamp(refusal.getBody()),
                    "every invalid chain shares one generic refusal — no existence leak");
        }
        assertEquals(Map.of("status", 403, "error", "Forbidden",
                        "message", "The selected assignment or branch is not available.",
                        "path", "/api/auth/context"),
                withoutTimestamp(mismatched.getBody()));
    }

    @Test
    void switchRequiresTheBranchTargetAndDerivesOrMatchesTheHospitalServerSide() {
        String token = login(NETWORK_ADMIN);
        UUID assignment = assignmentIdFor(NETWORK_ADMIN);
        UUID hospital1 = hospital(HOSPITAL_1).getId();
        UUID br101 = branch("HIACT-BR-101").getId();

        ResponseEntity<Map<String, Object>> missingBoth =
                switchResponse(token, assignment, null, null, "an ORGANIZATION switch must select its branch");
        ResponseEntity<Map<String, Object>> missingBranch =
                switchResponse(token, assignment, hospital1, null, "a hospital alone cannot select the chain");
        assertEquals(HttpStatus.FORBIDDEN, missingBoth.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, missingBranch.getStatusCode());
        assertEquals(withoutTimestamp(missingBoth.getBody()), withoutTimestamp(missingBranch.getBody()),
                "missing targets refuse with the same generic body");

        // FR-007: the branch id alone selects; its facility is derived from
        // server state and bound into the context — never the reverse.
        Map<String, Object> branchOnly = switchBody(token, assignment, null, br101);
        assertEquals(String.valueOf(hospital1),
                castMap(branchOnly.get("actingContext")).get("hospitalId"),
                "the acting hospital is the selected branch's own facility, server-derived");
        assertEquals(String.valueOf(br101), castMap(branchOnly.get("actingContext")).get("branchId"));

        String hospitalToken = login(HOSPITAL_ADMIN_1);
        UUID hospitalAssignment = assignmentIdFor(HOSPITAL_ADMIN_1);
        ResponseEntity<Map<String, Object>> hospitalWithoutBranch =
                switchResponse(hospitalToken, hospitalAssignment, null, null,
                        "a HOSPITAL switch demands the target branch");
        ResponseEntity<Map<String, Object>> hospitalFixedHospitalOnly =
                switchResponse(hospitalToken, hospitalAssignment, hospital1, null,
                        "the fixed hospital alone still misses the demanded branch");
        assertEquals(HttpStatus.FORBIDDEN, hospitalWithoutBranch.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, hospitalFixedHospitalOnly.getStatusCode());
        assertEquals(withoutTimestamp(hospitalWithoutBranch.getBody()),
                withoutTimestamp(hospitalFixedHospitalOnly.getBody()));
    }

    @Test
    void fixedScopesAcceptTheirOwnServerIssuedTargetsAndRefuseEveryOtherPair() {
        String nurseToken = login(BRANCH_NURSE_201);
        UUID nurseAssignment = assignmentIdFor(BRANCH_NURSE_201);
        UUID hospital2 = hospital(HOSPITAL_2).getId();
        UUID hospital1 = hospital(HOSPITAL_1).getId();
        UUID br201 = branch("HIACT-BR-201").getId();
        UUID br101 = branch("HIACT-BR-101").getId();

        ResponseEntity<Map<String, Object>> own = switchResponse(nurseToken, nurseAssignment, hospital2, br201,
                "the fixed pair of a BRANCH assignment is accepted");
        assertEquals(HttpStatus.OK, own.getStatusCode());
        assertEquals(String.valueOf(br201), castMap(own.getBody().get("actingContext")).get("branchId"));

        assertEquals(HttpStatus.FORBIDDEN,
                switchResponse(nurseToken, nurseAssignment, hospital1, br201, null).getStatusCode(),
                "a BRANCH assignment cannot claim a different hospital");
        assertEquals(HttpStatus.FORBIDDEN,
                switchResponse(nurseToken, nurseAssignment, hospital2, br101, null).getStatusCode(),
                "a BRANCH assignment cannot claim a different branch");

        String doctorToken = login(DEPARTMENT_DOCTOR_101);
        UUID doctorAssignment = assignmentIdFor(DEPARTMENT_DOCTOR_101);
        ResponseEntity<Map<String, Object>> ownChain =
                switchResponse(doctorToken, doctorAssignment, hospital1, br101, null);
        assertEquals(HttpStatus.OK, ownChain.getStatusCode(),
                "the department's server-derived chain is accepted verbatim");
        assertEquals(HttpStatus.FORBIDDEN,
                switchResponse(doctorToken, doctorAssignment, hospital1, branch("HIACT-BR-102").getId(), null)
                        .getStatusCode(),
                "a DEPARTMENT assignment cannot select another branch");
    }

    @Test
    void loginSkipsInactiveHospitalBranchesAndFailsClosedWithoutUsableTargets() {
        HospitalOrganization org = dedicatedOrganization();
        HospitalFacility dormantHospital = dedicatedHospital(org, "HIACT-HOSP-IDLE-" + suffix, "UTC");
        HospitalFacility liveHospital = dedicatedHospital(org, "HIACT-HOSP-LIVE-" + suffix, "America/New_York");
        // The dormant-hospital branch sorts FIRST by code, so a resolver that
        // ignores the hospital ancestor would deterministically bind it.
        Branch dormantBranch = dedicatedBranch(dormantHospital, "HIACT-BR-IDLE-" + suffix);
        Branch liveBranch = dedicatedBranch(liveHospital, "HIACT-BR-LOOP-" + suffix);
        assertTrue(dormantBranch.getCode().compareTo(liveBranch.getCode()) < 0,
                "fixture: the inactive-hospital branch is the deterministic first");

        UserAccount networkUser = accounts.save(new UserAccount("hiact-net-idle-" + suffix,
                encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        assignments.save(ActingAssignment.organization(networkUser, org, Role.NURSE));
        UserAccount hospitalUser = accounts.save(new UserAccount("hiact-hosp-idle-" + suffix,
                encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.NURSE)));
        assignments.save(ActingAssignment.hospital(hospitalUser, org, Role.NURSE, dormantHospital));
        jdbc.update("update hospitals set active = false where id = ?", dormantHospital.getId());

        Map<String, Object> healthy = loginBody(networkUser.getUsername());
        assertEquals(String.valueOf(liveHospital.getId()),
                castMap(healthy.get("actingContext")).get("hospitalId"),
                "the network login skips the inactive hospital and binds the first usable branch's facility");
        assertEquals(String.valueOf(liveBranch.getId()), castMap(healthy.get("actingContext")).get("branchId"));

        ResponseEntity<Map<String, Object>> refused = exchangeLogin(hospitalUser.getUsername());
        assertEquals(HttpStatus.UNAUTHORIZED, refused.getStatusCode(),
                "a hospital-scope assignment on an inactive facility cannot log in");
        assertEquals(Map.of("error", "Invalid username or password."), refused.getBody(),
                "the refusal is the non-enumerating credentials body");

        deactivateBranch(liveBranch.getId());
        ResponseEntity<Map<String, Object>> noTargets = exchangeLogin(networkUser.getUsername());
        assertEquals(HttpStatus.UNAUTHORIZED, noTargets.getStatusCode(),
                "no usable active branch under an active hospital — login fails closed");
        assertEquals(Map.of("error", "Invalid username or password."), noTargets.getBody());
    }

    @Test
    void tamperedHospitalChainClaimsStayUnauthenticatedWhileTheControlAuthenticates() {
        io.jsonwebtoken.Claims claims = Jwts.parser()
                .verifyWith(hmac(TEST_JWT_SECRET))
                .build()
                .parseSignedClaims(login(NETWORK_ADMIN))
                .getPayload();

        String control = reSigned(claims, claims.get("hospitalId", String.class));
        assertEquals(HttpStatus.OK, get("/api/dashboard", control).getStatusCode(),
                "the re-signed structurally identical token authenticates (mechanism control)");

        String tampered = reSigned(claims, hospital(HOSPITAL_2).getId().toString());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/dashboard", tampered).getStatusCode(),
                "a hospital claim that does not match the server-derived chain is unauthenticated");
    }

    // ------------------------------------------------------------------
    // Fixture helpers.
    // ------------------------------------------------------------------

    private HospitalOrganization ensureOrganization() {
        return organizations.findByCode(ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(ORG_CODE, "HierAct Synthetic Network")));
    }

    private HospitalOrganization organization() {
        return organizations.findByCode(ORG_CODE).orElseThrow();
    }

    private HospitalFacility ensureHospital(HospitalOrganization organization, String code, String zone) {
        return hospitals.findByOrganizationIdAndCode(organization.getId(), code).orElseGet(() ->
                hospitals.save(new HospitalFacility(organization, code, "Synthetic Hospital " + code,
                        "Synthetic Region " + code, zone)));
    }

    private HospitalFacility hospital(String code) {
        return hospitals.findByOrganizationIdAndCode(organization().getId(), code).orElseThrow();
    }

    private Branch ensureBranch(HospitalFacility hospital, String code) {
        return branches.findByHospitalIdAndCode(hospital.getId(), code).orElseGet(() ->
                branches.save(new Branch(hospital, code, "Synthetic Branch " + code,
                        "1 Synthetic Way", hospital.getTimeZone())));
    }

    private Branch branch(String code) {
        return branches.findByHospitalIdAndCode(hospital(HOSPITAL_1).getId(), code)
                .or(() -> branches.findByHospitalIdAndCode(hospital(HOSPITAL_2).getId(), code))
                .orElseThrow();
    }

    private Department ensureDepartment(Branch branch, String code) {
        return departments.findByBranchIdAndCode(branch.getId(), code).orElseGet(() ->
                departments.save(new Department(branch, code, "Synthetic Department " + code,
                        "general medicine", "Synthetic Wing")));
    }

    private HospitalOrganization dedicatedOrganization() {
        return organizations.save(new HospitalOrganization("HIACT-ORG-D-" + suffix,
                "HierAct Dedicated Network " + suffix));
    }

    private HospitalFacility dedicatedHospital(HospitalOrganization organization, String code, String zone) {
        return hospitals.save(new HospitalFacility(organization, code, "Synthetic Hospital " + code,
                "Synthetic Region " + code, zone));
    }

    private Branch dedicatedBranch(HospitalFacility hospital, String code) {
        return branches.save(new Branch(hospital, code, "Synthetic Branch " + code,
                "1 Synthetic Way", hospital.getTimeZone()));
    }

    private Department dedicatedDepartment(Branch branch, String code) {
        return departments.save(new Department(branch, code, "Synthetic Department " + code,
                "general medicine", "Synthetic Wing"));
    }

    private void ensureAccount(String username) {
        accounts.findByUsername(username).orElseGet(() ->
                accounts.save(new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD),
                        Set.of(Role.ADMIN))));
    }

    private void ensureAssignment(String username, Role role, AssignmentScope scope,
                                  HospitalOrganization organization, HospitalFacility hospital,
                                  Branch branch, Department department) {
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        boolean present = assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId())
                .stream().anyMatch(candidate -> candidate.getScope() == scope);
        if (present) {
            return;
        }
        ActingAssignment created = switch (scope) {
            case ORGANIZATION -> ActingAssignment.organization(account, organization, role);
            case HOSPITAL -> ActingAssignment.hospital(account, organization, role, hospital);
            case BRANCH -> ActingAssignment.branch(account, organization, role, branch);
            case DEPARTMENT -> transactionTemplate.execute(tx -> assignments.save(
                    ActingAssignment.department(accounts.findById(account.getId()).orElseThrow(), organization, role,
                            departments.findById(department.getId()).orElseThrow())));
        };
        if (created != null && scope != AssignmentScope.DEPARTMENT) {
            assignments.save(created);
        }
    }

    private UUID assignmentIdFor(String username) {
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        return assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(account.getId())
                .get(0).getId();
    }

    private void deactivateBranch(UUID branchId) {
        jdbc.update("update branches set active = false where id = ?", branchId);
    }

    // ------------------------------------------------------------------
    // HTTP helpers.
    // ------------------------------------------------------------------

    private Map<String, Object> loginBody(String username) {
        ResponseEntity<Map<String, Object>> response = exchangeLogin(username);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "fixture login must succeed for " + username);
        return response.getBody();
    }

    private String login(String username) {
        return String.valueOf(loginBody(username).get("accessToken"));
    }

    private ResponseEntity<Map<String, Object>> exchangeLogin(String username) {
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of("username", username, "password", TEST_ACCOUNT_PASSWORD)), MAP);
    }

    private Map<String, Object> switchBody(String token, UUID assignmentId, UUID hospitalId, UUID branchId) {
        ResponseEntity<Map<String, Object>> response = switchResponse(token, assignmentId, hospitalId, branchId, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "fixture switch must succeed");
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> switchResponse(String token, UUID assignmentId,
                                                               UUID hospitalId, UUID branchId, String because) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", assignmentId.toString());
        if (hospitalId != null) {
            payload.put("hospitalId", hospitalId.toString());
        }
        if (branchId != null) {
            payload.put("branchId", branchId.toString());
        }
        return rest.exchange("/api/auth/context", HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> assignmentList(Map<String, Object> loginResponse) {
        return (List<Map<String, Object>>) loginResponse.get("assignments");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private Map<String, Object> withoutTimestamp(Map<String, Object> body) {
        Map<String, Object> stable = new LinkedHashMap<>(body);
        stable.remove("timestamp");
        return stable;
    }

    private Set<String> allBodyIdValues(Map<String, Object> body) {
        Set<String> ids = new HashSet<>();
        collectIdStrings(body, ids);
        return ids;
    }

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

    private static SecretKey hmac(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Re-signs a captured claim set with one structural claim replaced. */
    private String reSigned(io.jsonwebtoken.Claims claims, String hospitalId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(claims.getSubject())
                .claim("assignmentId", claims.get("assignmentId", String.class))
                .claim("role", claims.get("role", String.class))
                .claim("scope", claims.get("scope", String.class))
                .claim("organizationId", claims.get("organizationId", String.class))
                .claim("hospitalId", hospitalId)
                .claim("branchId", claims.get("branchId", String.class));
        if (claims.get("departmentId") != null) {
            builder.claim("departmentId", claims.get("departmentId", String.class));
        }
        return builder.issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(30))))
                .signWith(hmac(TEST_JWT_SECRET))
                .compact();
    }
}

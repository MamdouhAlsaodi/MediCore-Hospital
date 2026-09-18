package com.mamtrex.hospital.organization;

import com.mamtrex.hospital.TestRuntimeSecrets;
import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.auth.UserAccount;
import com.mamtrex.hospital.auth.UserAccountRepository;
import com.mamtrex.hospital.department.DepartmentRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Department acting-context scope evidence (plan Task 5, steps 1-2; FR-013).
 *
 * Department reads and commands must derive their scope from the verified
 * acting assignment — never from a query parameter, body value, or header:
 * a BRANCH-scope assignment sees only its own branch's assigned rows; an
 * ORGANIZATION-scope ADMIN keeps the organization-wide hierarchy view;
 * unknown and legacy null-branch rows stay hidden for everyone; and no
 * denied command mutates anything or records a success audit event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:dept-scope-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DepartmentScopeApiTest {

    private static final String TEST_JWT_SECRET = TestRuntimeSecrets.jwtSecret();
    private static final String TEST_ACCOUNT_PASSWORD = TestRuntimeSecrets.accountPassword();

    private static final String ADMIN = "dept-admin";
    private static final String HR = "dept-hr";
    private static final String BRANCH_ADMIN = "dept-branch-admin";
    private static final String TEST_ORG_CODE = "DEPT-SCOPE-ORG";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void runtimeSecrets(DynamicPropertyRegistry registry) {
        registry.add("hospital.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("HOSPITAL_ADMIN_PASSWORD", () -> TEST_ACCOUNT_PASSWORD);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    @Autowired
    com.mamtrex.hospital.organization.HospitalFacilityRepository hospitals;

    @Autowired
    com.mamtrex.hospital.department.DepartmentRepository departments;

    /** Class-stable so the acting assignments bind to the same branches across test methods. */
    private static final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private com.mamtrex.hospital.organization.HospitalOrganization org;
    private com.mamtrex.hospital.organization.Branch branchA;
    private com.mamtrex.hospital.organization.Branch branchB;
    private String deptAId;
    private String deptBId;

    @BeforeEach
    void seed() {
        if (accounts.findByUsername(ADMIN).isEmpty()) {
            accounts.save(new UserAccount(ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        if (accounts.findByUsername(HR).isEmpty()) {
            accounts.save(new UserAccount(HR, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.HR)));
        }
        if (accounts.findByUsername(BRANCH_ADMIN).isEmpty()) {
            accounts.save(new UserAccount(BRANCH_ADMIN, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(Role.ADMIN)));
        }
        org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new com.mamtrex.hospital.organization.HospitalOrganization(
                        TEST_ORG_CODE, "Department Scope Demo Organization")));
        var fixtureHospital = com.mamtrex.hospital.organization.FixtureHospitals.ensureHospital(hospitals, org);
        branchA = branches.findByHospitalIdAndCode(fixtureHospital.getId(), suffix + "-a")
                .orElseGet(() -> branches.save(new com.mamtrex.hospital.organization.Branch(
                        fixtureHospital, suffix + "-a", "Demo Branch A", "1 Demo Campus")));
        branchB = branches.findByHospitalIdAndCode(fixtureHospital.getId(), suffix + "-b")
                .orElseGet(() -> branches.save(new com.mamtrex.hospital.organization.Branch(
                        fixtureHospital, suffix + "-b", "Demo Branch B", "2 Demo Campus")));
        UserAccount admin = accounts.findByUsername(ADMIN).orElseThrow();
        if (assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(admin.getId()).isEmpty()) {
            assignments.save(ActingAssignment.organization(admin, org, Role.ADMIN));
        }
        UserAccount hr = accounts.findByUsername(HR).orElseThrow();
        if (assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(hr.getId()).isEmpty()) {
            assignments.save(ActingAssignment.branch(hr, org, Role.HR, branchA));
        }
        // Department writes are ADMIN-role-only (hierarchy policy); this
        // BRANCH-scope ADMIN proves scope derivation on the write paths.
        UserAccount branchAdmin = accounts.findByUsername(BRANCH_ADMIN).orElseThrow();
        if (assignments.findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(branchAdmin.getId()).isEmpty()) {
            assignments.save(ActingAssignment.branch(branchAdmin, org, Role.ADMIN, branchA));
        }
        deptAId = createDepartment(adminToken(), branchA.getId(), "DEPT-A-" + suffix);
        deptBId = createDepartment(adminToken(), branchB.getId(), "DEPT-B-" + suffix);
    }

    /** A BRANCH-scope HR token lists only its own branch's departments. */
    @Test
    void branchScopeDepartmentListShowsOnlyActingBranchRows() {
        List<Map<String, Object>> rows = listJson("/api/departments", hrToken());
        assertFalse(rows.isEmpty(), "the acting branch's own departments must be visible");
        for (Map<String, Object> row : rows) {
            assertEquals(branchA.getId().toString(), row.get("branchId"),
                    "a BRANCH-scope read must only ever surface acting-branch rows");
        }
        assertTrue(rows.stream().noneMatch(row -> deptBId.equals(String.valueOf(row.get("id")))),
                "another branch's department must never appear");
    }

    /** A branch id in the query string is not authority: the list stays acting-scoped. */
    @Test
    void branchIdQueryStringIsNotAuthorityForTheList() {
        List<Map<String, Object>> rows = listJson(
                "/api/departments?branchId=" + branchB.getId(), hrToken());
        assertTrue(rows.stream().noneMatch(row -> branchB.getId().toString().equals(row.get("branchId"))),
                "a query-string branch id must never widen the read");
        for (Map<String, Object> row : rows) {
            assertEquals(branchA.getId().toString(), row.get("branchId"));
        }
    }

    /** A BRANCH-scope token cannot read another branch's department: generic 404. */
    @Test
    void branchScopeGetOfAnotherBranchsDepartmentIsGeneric404() {
        ResponseEntity<Map<String, Object>> response = getJson("/api/departments/" + deptBId, hrToken());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "a cross-branch department read is the shared safe 404");
    }

    /** A BRANCH-scope token cannot delete another branch's department: 404 and zero mutations. */
    @Test
    void branchScopeDeleteOfAnotherBranchsDepartmentIsRefusedWithoutMutation() {
        long before = departments.count();
        ResponseEntity<Void> response = deleteJson("/api/departments/" + deptBId, branchAdminToken());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "a cross-branch department delete is the shared safe 404");
        assertEquals(before, departments.count(), "a refused delete must mutate nothing");
    }

    /** ORGANIZATION-scope ADMIN keeps the organization-wide hierarchy view. */
    @Test
    void organizationScopeAdminKeepsTheOrganizationWideView() {
        List<Map<String, Object>> rows = listJson("/api/departments", adminToken());
        assertTrue(rows.stream().anyMatch(row -> deptAId.equals(String.valueOf(row.get("id")))),
                "the organization view includes branch A rows");
        assertTrue(rows.stream().anyMatch(row -> deptBId.equals(String.valueOf(row.get("id")))),
                "the organization view includes branch B rows");
    }

    /** A BRANCH-scope ADMIN create is owned by the acting branch; a body branchId is never authority. */
    @Test
    void branchScopeCreateIsOwnedByTheActingBranchRegardlessOfBodyBranchId() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("branchId", branchB.getId().toString());
        payload.put("code", "DEPT-BA-" + suffix);
        payload.put("name", "Demo Branch-Admin Created Department");
        payload.put("specialty", "general medicine");
        payload.put("location", "Demo Wing H");
        ResponseEntity<Map<String, Object>> response = postJson("/api/departments", branchAdminToken(), payload);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "a legal same-scope create must succeed");
        assertNotNull(response.getBody());
        assertEquals(branchA.getId().toString(), response.getBody().get("branchId"),
                "ownership is server-derived from the acting branch, never the body value");
    }

    /** Legacy null-branch rows stay hidden through every scoped read. */
    @Test
    void legacyNullBranchRowsStayHidden() {
        com.mamtrex.hospital.department.Department legacy = new com.mamtrex.hospital.department.Department(
                null, "LEGACY-DEPT-" + suffix, "Demo Legacy Department", "legacy", "nowhere");
        departments.save(legacy);
        assertTrue(listJson("/api/departments", adminToken()).stream()
                        .noneMatch(row -> ("LEGACY-DEPT-" + suffix).equals(row.get("code"))),
                "legacy unassigned rows never surface through the organization view");
        assertEquals(HttpStatus.NOT_FOUND, getJson("/api/departments/" + legacy.getId(), adminToken()).getStatusCode(),
                "legacy rows are the shared 404 on detail reads");
    }

    // --------------------------------------------------------- helpers

    private String adminToken() {
        return login(ADMIN);
    }

    private String hrToken() {
        return login(HR);
    }

    private String branchAdminToken() {
        return login(BRANCH_ADMIN);
    }

    private String login(String username) {
        ResponseEntity<Map<String, Object>> response = postJson("/api/auth/login", null, Map.of(
                "username", username, "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the synthetic account must log in");
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private String createDepartment(String token, UUID branchId, String code) {
        var existing = departments.findByBranchIdAndCode(branchId, code);
        if (existing.isPresent()) {
            return existing.get().getId().toString();
        }
        ResponseEntity<Map<String, Object>> response = postJson("/api/departments", token, Map.of(
                "branchId", branchId.toString(),
                "code", code,
                "name", "Demo Department " + code,
                "specialty", "general medicine",
                "location", "Demo Wing"));
        assertEquals(HttpStatus.OK, response.getStatusCode(), "department creation must succeed");
        return response.getBody().get("id").toString();
    }

    private List<Map<String, Object>> listJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers), LIST);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the list read must succeed");
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    private ResponseEntity<Void> deleteJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, String token, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, headers), MAP);
    }
}

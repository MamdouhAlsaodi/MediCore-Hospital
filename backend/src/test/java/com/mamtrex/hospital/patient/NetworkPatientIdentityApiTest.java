package com.mamtrex.hospital.patient;

import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 US3 network patient identity API tests (specs/005 tasks T062;
 * data-model.md "Patient access" invariants). Against an isolated in-memory
 * H2 database with a synthetic three-hospital network it proves:
 *
 * <ul>
 *   <li>a patient created at hospital A is invisible to hospitals B and C —
 *       empty list, empty search, and the generic 404 on get/update, never a
 *       scoped error that would disclose existence;</li>
 *   <li>an explicit ACTIVE access grant makes the patient discoverable at
 *       hospital B only; hospital C still never sees it;</li>
 *   <li>create issues exactly one ACTIVE LOCAL_REGISTRATION grant for the
 *       registering hospital, atomically with the patient;</li>
 *   <li>a duplicate MRN create from another hospital finds the network row:
 *       one winner, the shared 409, never a second patient;</li>
 *   <li>the same hospital's other branches CAN resolve the patient (the
 *       hospital is the visibility unit now), preserving US1/US2 behavior
 *       as adapted to same-hospital branches; and</li>
 *   <li>a dependent workflow (invoice) at a foreign hospital 404s on the
 *       foreign patient reference.</li>
 * </ul>
 *
 * Synthetic test-only secrets and fabricated record values; no real personal
 * or clinical data is ever used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:network-patient-identity-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + NetworkPatientIdentityApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + NetworkPatientIdentityApiTest.TEST_ACCOUNT_PASSWORD
})
class NetworkPatientIdentityApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-netid-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-netid-01";

    private static final String USER_A = "netid-user-a";
    private static final String USER_A2 = "netid-user-a2";
    private static final String USER_B = "netid-user-b";
    private static final String USER_B_BILLING = "netid-user-b-billing";
    private static final String USER_C = "netid-user-c";

    private static final String ORG_CODE = "NETID-ORG";
    private static final String HOSPITAL_A_CODE = "NETID-HOSP-A";
    private static final String HOSPITAL_B_CODE = "NETID-HOSP-B";
    private static final String HOSPITAL_C_CODE = "NETID-HOSP-C";
    private static final String BRANCH_A_CODE = "NETID-BR-A";
    private static final String BRANCH_A2_CODE = "NETID-BR-A2";
    private static final String BRANCH_B_CODE = "NETID-BR-B";
    private static final String BRANCH_C_CODE = "NETID-BR-C";

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
    PatientHospitalAccessRepository accessGrants;

    @Autowired
    PatientRepository patients;

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private HospitalFacility hospitalA;
    private HospitalFacility hospitalB;
    private HospitalFacility hospitalC;

    @BeforeEach
    void seedDisposableNetwork() {
        HospitalOrganization org = organizations.findByCode(ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(ORG_CODE, "Synthetic Network Identity Org")));
        hospitalA = ensureHospital(org, HOSPITAL_A_CODE, "Identity Hospital A");
        hospitalB = ensureHospital(org, HOSPITAL_B_CODE, "Identity Hospital B");
        hospitalC = ensureHospital(org, HOSPITAL_C_CODE, "Identity Hospital C");
        Branch branchA = ensureBranch(hospitalA, BRANCH_A_CODE, "Identity Branch A");
        Branch branchA2 = ensureBranch(hospitalA, BRANCH_A2_CODE, "Identity Branch A2");
        Branch branchB = ensureBranch(hospitalB, BRANCH_B_CODE, "Identity Branch B");
        Branch branchC = ensureBranch(hospitalC, BRANCH_C_CODE, "Identity Branch C");
        ensureUser(USER_A, Role.RECEPTIONIST, org, branchA);
        ensureUser(USER_A2, Role.RECEPTIONIST, org, branchA2);
        ensureUser(USER_B, Role.RECEPTIONIST, org, branchB);
        ensureUser(USER_B_BILLING, Role.BILLING, org, branchB);
        ensureUser(USER_C, Role.RECEPTIONIST, org, branchC);
    }

    /** A patient created at A is invisible to B and C until an explicit grant exists. */
    @Test
    void patientCreatedAtAIsInvisibleToOtherHospitalsUntilExplicitGrant() {
        String tokenA = login(USER_A);
        String tokenB = login(USER_B);
        String tokenC = login(USER_C);
        String patientId = createPatient(tokenA, "invisible", "Invisible Patient " + suffix);

        // Hospital B: no discovery of any kind.
        assertTrue(getList("/api/patients", tokenB).getBody().isEmpty(),
                "hospital B must not list a patient it holds no grant for");
        assertTrue(getSearch("/api/patients?q={q}", tokenB, "Invisible Patient " + suffix).getBody().isEmpty(),
                "hospital B must not discover the patient through search");
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/patients/" + patientId, tokenB).getStatusCode(),
                "hospital B must receive the generic 404, never a scoped refusal");
        assertEquals(HttpStatus.NOT_FOUND, put("/api/patients/" + patientId, tokenB,
                Map.of("fullName", "Hijacked " + suffix, "phone", "", "email", "", "address", "")).getStatusCode(),
                "hospital B must not be able to mutate a patient it cannot see");

        // Hospital C: equally blind.
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/patients/" + patientId, tokenC).getStatusCode(),
                "hospital C must receive the generic 404");
        assertTrue(getList("/api/patients", tokenC).getBody().isEmpty(),
                "hospital C must not list the patient");

        // The explicit grant makes the patient discoverable at B — and only at B.
        grant(hospitalB, patientId);
        ResponseEntity<Map<String, Object>> seen = getMap("/api/patients/" + patientId, tokenB);
        assertEquals(HttpStatus.OK, seen.getStatusCode(), "an explicit ACTIVE grant must enable discovery at B");
        assertEquals(patientId, String.valueOf(seen.getBody().get("id")));
        assertTrue(getList("/api/patients", tokenB).getBody().stream()
                .anyMatch(p -> patientId.equals(String.valueOf(p.get("id")))), "B's list must contain the granted patient");
        assertEquals(HttpStatus.NOT_FOUND, getStatus("/api/patients/" + patientId, tokenC).getStatusCode(),
                "hospital C still never sees the patient granted only to B");
    }

    /** Create issues exactly one ACTIVE LOCAL_REGISTRATION grant for the registering hospital. */
    @Test
    void createIssuesExactlyOneLocalRegistrationGrantAtomically() {
        String tokenA = login(USER_A);
        String patientId = createPatient(tokenA, "grant", "Granted Patient " + suffix);
        UUID pid = UUID.fromString(patientId);
        List<PatientHospitalAccess> grants = accessGrants.findAll().stream()
                .filter(g -> pid.equals(g.getPatient().getId())).toList();
        assertEquals(1, grants.size(), "exactly one access grant must be created with the patient");
        PatientHospitalAccess grant = grants.get(0);
        assertEquals(PatientAccessStatus.ACTIVE, grant.getStatus(), "the registration grant must be ACTIVE");
        assertEquals(PatientAccessSource.LOCAL_REGISTRATION, grant.getSource(),
                "a create-time grant must be a LOCAL_REGISTRATION grant");
        assertEquals(hospitalA.getId(), grant.getHospital().getId(),
                "the grant must belong to the registering hospital");
        assertNull(grant.getSourceTransferId(), "a registration grant carries no transfer provenance");
        assertNull(grant.getRevokedAt(), "an ACTIVE grant must carry no revocation timestamp");
    }

    /** The global MRN uniqueness is network-wide: a foreign hospital's duplicate create finds one winner. */
    @Test
    void duplicateMrnAcrossHospitalsHasExactlyOneWinner() {
        String tokenA = login(USER_A);
        String tokenB = login(USER_B);
        String mrn = "MRN-NETID-" + suffix + "-DUP";
        createPatient(tokenA, "dup", "Winner Patient " + suffix, mrn);

        ResponseEntity<Map<String, Object>> conflict = post("/api/patients", tokenB, Map.of(
                "medicalRecordNumber", mrn,
                "fullName", "Loser Patient " + suffix));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(),
                "the duplicate MRN create from another hospital must be the shared 409");
        assertEquals(1, patients.findAll().stream()
                        .filter(p -> mrn.equals(p.getMedicalRecordNumber())).count(),
                "exactly one patient row may exist for the network MRN");
        // And the loser still cannot see the winner.
        assertEquals(HttpStatus.NOT_FOUND, patients.findAll().stream()
                .filter(p -> mrn.equals(p.getMedicalRecordNumber()))
                .findFirst().map(p -> getStatus("/api/patients/" + p.getId(), tokenB).getStatusCode())
                .orElseThrow(), "the duplicate-create loser must not gain visibility of the winner");
    }

    /** The hospital is the visibility unit: another branch of the SAME hospital can resolve the patient. */
    @Test
    void sameHospitalOtherBranchResolvesThePatient() {
        String tokenA = login(USER_A);
        String tokenA2 = login(USER_A2);
        String patientId = createPatient(tokenA, "samehosp", "Same Hospital Patient " + suffix);
        assertEquals(HttpStatus.OK, getStatus("/api/patients/" + patientId, tokenA2).getStatusCode(),
                "another branch of the same (grant-holding) hospital must resolve the patient");
    }

    /** A dependent workflow at a foreign hospital 404s on the foreign patient reference. */
    @Test
    void dependentWorkflowAtForeignHospitalRefusesTheForeignPatient() {
        String tokenA = login(USER_A);
        String tokenBBilling = login(USER_B_BILLING);
        String patientId = createPatient(tokenA, "foreign", "Foreign Ref Patient " + suffix);
        ResponseEntity<Map<String, Object>> invoice = post("/api/invoices", tokenBBilling, Map.of(
                "patientId", patientId,
                "invoiceNumber", "INV-NETID-" + suffix,
                "amount", "10.00",
                "currency", "USD"));
        assertEquals(HttpStatus.NOT_FOUND, invoice.getStatusCode(),
                "hospital B billing must not be able to invoice hospital A's patient");
    }

    // ------------------------------------------------------------ helpers

    private HospitalFacility ensureHospital(HospitalOrganization org, String code, String name) {
        return hospitals.findByOrganizationIdAndCode(org.getId(), code).orElseGet(() ->
                hospitals.save(new HospitalFacility(org, code, name, "Identity Region", "UTC")));
    }

    private Branch ensureBranch(HospitalFacility hospital, String code, String name) {
        return branches.findByHospitalIdAndCode(hospital.getId(), code).orElseGet(() ->
                branches.save(new Branch(hospital, code, name, "1 Identity Way")));
    }

    private void ensureUser(String username, Role role, HospitalOrganization org, Branch branch) {
        if (accounts.findByUsername(username).isEmpty()) {
            accounts.save(new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(role)));
        }
        UserAccount account = accounts.findByUsername(username).orElseThrow();
        boolean present = assignments
                .findByAccountIdAndRoleAndScopeAndBranchId(account.getId(), role, AssignmentScope.BRANCH, branch.getId())
                .isPresent();
        if (!present) {
            assignments.save(ActingAssignment.branch(account, org, role, branch));
        }
    }

    /** Test-only explicit grant seam (an API grant flow arrives with US4 transfers). */
    private void grant(HospitalFacility hospital, String patientId) {
        Patient patient = patients.findById(UUID.fromString(patientId)).orElseThrow();
        accessGrants.save(new PatientHospitalAccess(patient, hospital,
                PatientAccessSource.LEGACY_MIGRATION, null));
    }

    private String createPatient(String token, String tag, String fullName) {
        return createPatient(token, tag, fullName, "MRN-NETID-" + suffix + "-" + tag);
    }

    private String createPatient(String token, String tag, String fullName, String mrn) {
        ResponseEntity<Map<String, Object>> created = post("/api/patients", token, Map.of(
                "medicalRecordNumber", mrn,
                "fullName", fullName));
        assertEquals(HttpStatus.OK, created.getStatusCode(), "the synthetic patient fixture must register cleanly");
        return String.valueOf(created.getBody().get("id"));
    }

    private String login(String username) {
        ResponseEntity<Map<String, Object>> res = post("/api/auth/login", null, Map.of(
                "username", username, "password", TEST_ACCOUNT_PASSWORD));
        assertEquals(HttpStatus.OK, res.getStatusCode(), "login should succeed for " + username);
        return String.valueOf(res.getBody().get("accessToken"));
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

    private ResponseEntity<List<Map<String, Object>>> getSearch(String path, String token, String query) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}, query);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}

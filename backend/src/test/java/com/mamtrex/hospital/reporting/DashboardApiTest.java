package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
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
import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.billing.Invoice;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.emergency.EmergencyVisit;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dashboard contract suite (docs/plan2.md Task 5, packet
 * MEDICORE-PLAN2-TASK5-035): GET /api/dashboard keeps the five original
 * total keys and adds flat status-aware count keys, in a deterministic
 * insertion order, over an isolated in-memory H2 database with synthetic
 * records only (never the production file store, no real personal or
 * clinical data).
 *
 * <p>The exact-key-set and all-zero assertions rely on the fresh
 * create-drop database, so the zero test is pinned to run before the
 * seeding test through {@link Order}.</p>
 *
 * <p>Authentication policy (any authenticated role, anonymous 401) is
 * already pinned by SecurityAuthorizationTest and is not duplicated here;
 * this suite only pins the role most at risk of accidental exclusion
 * (BILLING is isolated to invoices elsewhere) plus the read-only/no-audit
 * semantics that belong to this endpoint's own contract.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + DashboardApiTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + DashboardApiTest.TEST_ACCOUNT_PASSWORD
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardApiTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-dashboard-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-dashboard-01";

    private static final String ADMIN_USER = "dashboard-admin";
    private static final String BILLING_USER = "dashboard-billing";

    /** Task 5 contract: exact keys in the exact deterministic response order. */
    private static final List<String> DASHBOARD_KEY_ORDER = List.of(
            "patients", "appointments", "admissions", "emergencyVisits", "invoices",
            "openAdmissions", "activeEmergencyVisits",
            "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PatientRepository patients;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    AdmissionRepository admissions;

    @Autowired
    EmergencyVisitRepository emergencyVisits;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private static final String TEST_ORG_CODE = "DASHBOARD-ORG";
    private static final String TEST_BRANCH_CODE = "DASHBOARD-BR-DEFAULT";

    /**
     * Task 3 seeding: the synthetic accounts log in through explicit enabled
     * acting assignments on a stable synthetic organization and default
     * branch — no global-role fallback exists.
     */
    @BeforeEach
    void seedDisposableAccounts() {
        for (String username : List.of(ADMIN_USER, BILLING_USER)) {
            if (accounts.findByUsername(username).isEmpty()) {
                accounts.save(new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD),
                        Set.of(username.equals(BILLING_USER) ? Role.BILLING : Role.ADMIN)));
            }
        }
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Synthetic Dashboard Hospital")));
        Branch branch = branches.findByOrganizationIdAndCode(org.getId(), TEST_BRANCH_CODE).orElseGet(() ->
                branches.save(new Branch(org, TEST_BRANCH_CODE, "Synthetic Dashboard Branch", "1 Dashboard Way")));
        ensureAssignment(ADMIN_USER, Role.ADMIN, AssignmentScope.ORGANIZATION, org, null);
        ensureAssignment(BILLING_USER, Role.BILLING, AssignmentScope.BRANCH, org, branch);
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

    /**
     * Fresh-database contract: BILLING (the role isolated to invoices
     * elsewhere) still reaches the dashboard, the response carries exactly
     * the eleven contract keys in deterministic insertion order, the five
     * original totals are present, and every care-operation status key is an
     * honest zero on an empty database — zeros are reported, never hidden.
     */
    @Test
    @Order(1)
    void dashboardExposesExactContractKeysWithAllZeroStatusCountsOnFreshData() {
        String token = login(BILLING_USER);
        ResponseEntity<Map<String, Object>> response = getDashboard(token);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "BILLING must reach the read-only dashboard like any authenticated role");
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "dashboard response must carry a body");
        assertEquals(DASHBOARD_KEY_ORDER, new ArrayList<>(body.keySet()),
                "the dashboard must expose exactly the Task 5 keys in deterministic insertion order");

        for (String key : DASHBOARD_KEY_ORDER) {
            assertInstanceOf(Number.class, body.get(key), "dashboard key '" + key + "' must be a raw count");
        }
        for (String key : List.of("patients", "appointments", "admissions", "emergencyVisits", "invoices")) {
            assertEquals(0L, count(body, key), "the fresh database must report total '" + key + "' as 0");
        }
        for (String key : List.of("openAdmissions", "activeEmergencyVisits",
                "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid")) {
            assertEquals(0L, count(body, key),
                    "the fresh database must report status key '" + key + "' as an honest 0");
        }
    }

    /**
     * Aggregation contract over an exact synthetic seed: totals count whole
     * rows, status buckets count only their own statuses (a DISCHARGED
     * admission must not count as open, a CLOSED visit must not count as
     * active, and each terminal invoice status counts only its own bucket),
     * and reading the dashboard is side-effect free: a repeated read returns
     * identical values and no audit events are produced by reads.
     */
    @Test
    @Order(2)
    void dashboardAggregatesExactStatusBucketsSideEffectFree() {
        String token = login(ADMIN_USER);
        seedExactCohort();

        ResponseEntity<Map<String, Object>> response = getDashboard(token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        assertEquals(2L, count(body, "patients"), "patients must count whole patient rows");
        assertEquals(3L, count(body, "appointments"), "appointments must count whole appointment rows");
        assertEquals(3L, count(body, "admissions"), "admissions must count whole admission rows");
        assertEquals(4L, count(body, "emergencyVisits"), "emergencyVisits must count whole visit rows");
        assertEquals(4L, count(body, "invoices"), "invoices must count whole invoice rows");

        assertEquals(2L, count(body, "openAdmissions"),
                "openAdmissions must count only ADMITTED (the DISCHARGED row must not count)");
        assertEquals(3L, count(body, "activeEmergencyVisits"),
                "activeEmergencyVisits must count only WAITING + IN_TREATMENT (the CLOSED row must not count)");
        assertEquals(1L, count(body, "invoicesDraft"), "invoicesDraft must count only DRAFT");
        assertEquals(1L, count(body, "invoicesIssued"), "invoicesIssued must count only ISSUED");
        assertEquals(1L, count(body, "invoicesPaid"), "invoicesPaid must count only PAID");
        assertEquals(1L, count(body, "invoicesVoid"), "invoicesVoid must count only VOID");

        ResponseEntity<Map<String, Object>> repeat = getDashboard(token);
        assertEquals(HttpStatus.OK, repeat.getStatusCode());
        assertEquals(body, repeat.getBody(), "a repeated read must be identical — the dashboard stays side-effect free");

        int auditBefore = auditEventCount();
        getDashboard(token);
        assertEquals(auditBefore, auditEventCount(),
                "reading the dashboard must not produce any audit event");
    }

    // ------------------------------------------------------------------
    // Synthetic seeding (exact cohort; unique suffix keeps rows disposable)
    // ------------------------------------------------------------------

    private void seedExactCohort() {
        Patient first = patients.save(new Patient("MRN-DASH-A-" + suffix, "Synthetic Dashboard Patient A",
                null, null, null, null, null, null));
        Patient second = patients.save(new Patient("MRN-DASH-B-" + suffix, "Synthetic Dashboard Patient B",
                null, null, null, null, null, null));
        String firstId = first.getId().toString();

        for (int i = 0; i < 3; i++) {
            appointments.save(new Appointment(firstId, "raw-professional-ref", "2031-01-0" + (i + 1) + "T09:00",
                    "Consultation", "scheduled"));
        }

        admissions.save(new Admission(firstId, "2031-01-01T08:00", null, "synthetic open stay A", "ADMITTED"));
        admissions.save(new Admission(firstId, "2031-01-02T08:00", null, "synthetic open stay B", "ADMITTED"));
        admissions.save(new Admission(firstId, "2031-01-03T08:00", "2031-01-05T09:30",
                "synthetic discharged stay", "DISCHARGED"));

        emergencyVisits.save(new EmergencyVisit(firstId, "2031-01-01T09:00", "3", "synthetic waiting visit", "WAITING"));
        emergencyVisits.save(new EmergencyVisit(firstId, "2031-01-02T09:00", "2", "synthetic waiting visit", "WAITING"));
        emergencyVisits.save(new EmergencyVisit(second.getId().toString(), "2031-01-03T09:00", "4",
                "synthetic treated visit", "IN_TREATMENT"));
        emergencyVisits.save(new EmergencyVisit(second.getId().toString(), "2031-01-04T09:00", "5",
                "synthetic closed visit", "CLOSED"));

        invoices.save(new Invoice(firstId, "INV-DASH-DRAFT-" + suffix, "10.00", "USD", "DRAFT"));
        invoices.save(new Invoice(firstId, "INV-DASH-ISSUED-" + suffix, "20.00", "USD", "ISSUED"));
        invoices.save(new Invoice(second.getId().toString(), "INV-DASH-PAID-" + suffix, "30.00", "USD", "PAID"));
        invoices.save(new Invoice(second.getId().toString(), "INV-DASH-VOID-" + suffix, "40.00", "USD", "VOID"));
    }

    // ------------------------------------------------------------------
    // HTTP and audit helpers
    // ------------------------------------------------------------------

    private String login(String username) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", TEST_ACCOUNT_PASSWORD), loginHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        assertEquals(HttpStatus.OK, response.getStatusCode(), "the synthetic account must log in");
        return String.valueOf(response.getBody().get("accessToken"));
    }

    private ResponseEntity<Map<String, Object>> getDashboard(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/dashboard", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    private long count(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }

    private int auditEventCount() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login(ADMIN_USER));
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange("/api/audit", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> events = response.getBody();
        assertNotNull(events, "the audit endpoint must return a list");
        return events.size();
    }
}

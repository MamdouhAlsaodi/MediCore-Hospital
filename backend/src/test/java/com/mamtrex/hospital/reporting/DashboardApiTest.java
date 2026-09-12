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
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Command-center contract suite (docs/plan3.md Task 10, §4.7): over real
 * HTTP and an isolated in-memory H2 database with synthetic records only,
 * it pins the typed branch summary (exact keys in declaration order,
 * all-zero and populated, today's boundary under the server's explicit
 * clock, bed and invoice buckets, cross-branch isolation), the
 * organization-scoped ADMIN network view (deterministic shape and order,
 * totals equal to the sum of the branch summaries, honest zero branches,
 * ordinary denial for every other context), and the retained
 * {@code /api/dashboard} alias as a branch-summary-only path that never
 * falls back to whole-table counts.
 *
 * <p>The server clock is pinned by a test-only {@code @Primary} bean to a
 * fixed UTC instant, so "today" is deterministic instead of wall-clock
 * dependent; no production behavior reads wall time through any other
 * path. Each test seeds with a unique suffix and asserts relative to a
 * baseline read, so the shared database never couples tests to an order —
 * except the all-zero pin, which stays first through {@link Order}.</p>
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

    /** The pinned server day: every "today" assertion names 2037-01-04 (UTC). */
    private static final String TODAY = "2037-01-04";

    /** Exact Task 10 branch-summary key contract, in JSON declaration order. */
    private static final List<String> BRANCH_KEY_ORDER = List.of(
            "branchId", "branchCode", "branchName",
            "patients", "appointments", "admissions", "emergencyVisits", "invoices",
            "openAdmissions", "activeEmergencyVisits",
            "bedsAvailable", "bedsOccupied", "bedsMaintenance", "bedsOutOfService",
            "todayAppointments",
            "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid");

    /** Exact Task 10 network-summary key contract, in JSON declaration order. */
    private static final List<String> NETWORK_KEY_ORDER = List.of(
            "organizationId", "organizationName",
            "patients", "appointments", "admissions", "emergencyVisits", "invoices",
            "openAdmissions", "activeEmergencyVisits",
            "bedsAvailable", "bedsOccupied", "bedsMaintenance", "bedsOutOfService",
            "todayAppointments",
            "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid",
            "branches");

    private static final String ADMIN_USER = "dashboard-admin";
    private static final String BILLING_USER = "dashboard-billing";
    private static final String NURSE_USER = "dashboard-nurse";

    private static final String TEST_ORG_CODE = "DASHBOARD-ORG";
    private static final String TEST_BRANCH_CODE = "DASHBOARD-BR-DEFAULT";

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
    BedRepository beds;

    @Autowired
    ActingAssignmentRepository assignments;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    /** Pins the server's explicit clock so the today boundary is deterministic. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedDashboardClock {
        @Bean
        @Primary
        Clock fixedDashboardClock() {
            return Clock.fixed(Instant.parse(TODAY + "T10:15:00Z"), ZoneOffset.UTC);
        }
    }

    /** Unique synthetic suffix per test instance keeps every record disposable. */
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Task 3 seeding: the synthetic accounts log in through explicit enabled
     * acting assignments on a stable synthetic organization and default
     * branch — no global-role fallback exists. The nurse account exists to
     * prove the network denial is a role decision, not only a scope one.
     */
    @BeforeEach
    void seedDisposableAccounts() {
        for (String username : List.of(ADMIN_USER, BILLING_USER, NURSE_USER)) {
            if (accounts.findByUsername(username).isEmpty()) {
                Role role = switch (username) {
                    case BILLING_USER -> Role.BILLING;
                    case NURSE_USER -> Role.NURSE;
                    default -> Role.ADMIN;
                };
                accounts.save(new UserAccount(username, encoder.encode(TEST_ACCOUNT_PASSWORD), Set.of(role)));
            }
        }
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseGet(() ->
                organizations.save(new HospitalOrganization(TEST_ORG_CODE, "Synthetic Dashboard Hospital")));
        Branch branch = branches.findByOrganizationIdAndCode(org.getId(), TEST_BRANCH_CODE).orElseGet(() ->
                branches.save(new Branch(org, TEST_BRANCH_CODE, "Synthetic Dashboard Branch", "1 Dashboard Way")));
        ensureAssignment(ADMIN_USER, Role.ADMIN, AssignmentScope.ORGANIZATION, org, null);
        ensureAssignment(BILLING_USER, Role.BILLING, AssignmentScope.BRANCH, org, branch);
        ensureAssignment(NURSE_USER, Role.NURSE, AssignmentScope.ORGANIZATION, org, null);
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
     * Fresh-database contract: the branch summary is reachable for a
     * branch-scoped non-ADMIN role, carries exactly the nineteen Task 10
     * keys in deterministic declaration order, names the acting branch from
     * server state, and reports an honest zero for every metric on an empty
     * branch — zeros are reported, never hidden.
     */
    @Test
    @Order(1)
    void branchSummaryExposesExactContractKeysWithAllZeroCountsOnFreshData() {
        Branch defaultBranch = defaultBranch();
        ResponseEntity<Map<String, Object>> response = getJson("/api/dashboard/branch", login(BILLING_USER));
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "the branch summary stays reachable for every authenticated role");
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the branch summary must carry a body");
        assertEquals(BRANCH_KEY_ORDER, new ArrayList<>(body.keySet()),
                "the branch summary must expose exactly the Task 10 keys in declaration order");
        assertEquals(defaultBranch.getId().toString(), body.get("branchId"),
                "the branch identity must come from the verified acting context");
        assertEquals(defaultBranch.getCode(), body.get("branchCode"));
        assertEquals(defaultBranch.getName(), body.get("branchName"));
        for (String key : BRANCH_KEY_ORDER.subList(3, BRANCH_KEY_ORDER.size())) {
            assertEquals(0L, count(body, key),
                    "the fresh branch must report '" + key + "' as an honest 0");
        }
    }

    /**
     * Populated contract over an exact synthetic seed: totals count whole
     * rows owned by the acting branch, status buckets count only their own
     * statuses (a DISCHARGED admission is not open, a CLOSED visit is not
     * active, each terminal invoice status counts only its own bucket, and
     * every canonical bed status counts only its own bucket), today's
     * appointments come from the server clock's half-open day window, a
     * repeated read is identical, and reads record no audit events.
     */
    @Test
    @Order(2)
    void branchSummaryAggregatesExactScopedBucketsSideEffectFree() {
        String token = login(ADMIN_USER);
        Map<String, Object> baseline = readBranch(token);

        seedCohort(defaultBranch());

        Map<String, Object> body = readBranch(token);
        assertEquals(BRANCH_KEY_ORDER, new ArrayList<>(body.keySet()));
        assertEquals(2L, count(body, "patients") - count(baseline, "patients"),
                "patients must count whole patient rows owned by the branch");
        assertEquals(8L, count(body, "appointments") - count(baseline, "appointments"),
                "appointments must count whole appointment rows (3 future + 5 boundary)");
        assertEquals(3L, count(body, "admissions") - count(baseline, "admissions"),
                "admissions must count whole admission rows");
        assertEquals(4L, count(body, "emergencyVisits") - count(baseline, "emergencyVisits"),
                "emergencyVisits must count whole visit rows");
        assertEquals(4L, count(body, "invoices") - count(baseline, "invoices"),
                "invoices must count whole invoice rows");
        assertEquals(2L, count(body, "openAdmissions") - count(baseline, "openAdmissions"),
                "openAdmissions must count only ADMITTED (the DISCHARGED row must not count)");
        assertEquals(3L, count(body, "activeEmergencyVisits") - count(baseline, "activeEmergencyVisits"),
                "activeEmergencyVisits must count only WAITING + IN_TREATMENT (the CLOSED row must not count)");
        assertEquals(1L, count(body, "bedsAvailable") - count(baseline, "bedsAvailable"),
                "bedsAvailable must count only AVAILABLE beds");
        assertEquals(1L, count(body, "bedsOccupied") - count(baseline, "bedsOccupied"),
                "bedsOccupied counts only the admission-owned OCCUPIED state");
        assertEquals(1L, count(body, "bedsMaintenance") - count(baseline, "bedsMaintenance"),
                "bedsMaintenance must count only MAINTENANCE beds");
        assertEquals(1L, count(body, "bedsOutOfService") - count(baseline, "bedsOutOfService"),
                "bedsOutOfService must count only OUT_OF_SERVICE beds");
        assertEquals(1L, count(body, "invoicesDraft") - count(baseline, "invoicesDraft"),
                "invoicesDraft must count only DRAFT");
        assertEquals(1L, count(body, "invoicesIssued") - count(baseline, "invoicesIssued"),
                "invoicesIssued must count only ISSUED");
        assertEquals(1L, count(body, "invoicesPaid") - count(baseline, "invoicesPaid"),
                "invoicesPaid must count only PAID");
        assertEquals(1L, count(body, "invoicesVoid") - count(baseline, "invoicesVoid"),
                "invoicesVoid must count only VOID");
        assertEquals(3L, count(body, "todayAppointments") - count(baseline, "todayAppointments"),
                "todayAppointments must count only the server clock's day window (3 of the 5 boundary rows)");

        assertEquals(body, readBranch(token),
                "a repeated read must be identical — the dashboard stays side-effect free");
        int auditBefore = auditEventCount();
        readBranch(token);
        assertEquals(auditBefore, auditEventCount(),
                "reading the branch summary must not produce any audit event");
    }

    /**
     * The today boundary belongs to the server's explicit clock: on the
     * pinned day 2037-01-04, the midnight-start instant counts (inclusive),
     * the day's last minute counts, and the seconds-past-midnight row
     * counts, while yesterday's last minute and tomorrow's first instant do
     * not. The total appointment count moves by five — only the window
     * decides today's bucket.
     */
    @Test
    @Order(3)
    void todayBoundaryIsOwnedByTheServerClock() {
        String token = login(ADMIN_USER);
        Branch branch = defaultBranch();
        Map<String, Object> baseline = readBranch(token);

        seedAppointment(branch, TODAY + "T00:00");
        seedAppointment(branch, TODAY + "T00:00:30");
        seedAppointment(branch, TODAY + "T23:59");
        seedAppointment(branch, "2037-01-03T23:59");
        seedAppointment(branch, "2037-01-05T00:00");

        Map<String, Object> body = readBranch(token);
        assertEquals(5L, count(body, "appointments") - count(baseline, "appointments"),
                "every seeded row must count toward the whole-row total");
        assertEquals(3L, count(body, "todayAppointments") - count(baseline, "todayAppointments"),
                "the server clock's half-open day window must own the today bucket");
    }

    /**
     * Cross-branch isolation: rows of another branch — and legacy rows with
     * no branch at all — never move a branch summary. Each acting branch
     * sees exactly its own data, derived from the verified context.
     */
    @Test
    @Order(4)
    void branchSummaryIsolatesBranchesAndLegacyRows() {
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseThrow();
        Branch east = branches.findByOrganizationIdAndCode(org.getId(), "DASHBOARD-BR-EAST-" + suffix)
                .orElseGet(() -> branches.save(new Branch(org, "DASHBOARD-BR-EAST-" + suffix,
                        "Synthetic East Branch " + suffix, "9 East Way")));
        Branch defaultBranch = defaultBranch();

        // Baseline first: earlier cohort tests may already own rows in the
        // default branch, so isolation is pinned by relative deltas.
        String defaultToken = tokenActingOn(defaultBranch);
        Map<String, Object> baselineDefault = readBranch(defaultToken);

        patients.save(new Patient(null, "MRN-DASH-LEGACY-" + suffix, "Synthetic Legacy Patient",
                null, null, null, null, null, null));
        patients.save(new Patient(east, "MRN-DASH-EAST-" + suffix, "Synthetic East Patient",
                null, null, null, null, null, null));

        Map<String, Object> defaultSummary = readBranch(defaultToken);
        assertEquals(defaultBranch.getId().toString(), defaultSummary.get("branchId"));
        assertEquals(count(baselineDefault, "patients"), count(defaultSummary, "patients"),
                "the default branch must not see the east branch's patient or the legacy row");

        Map<String, Object> eastSummary = readBranch(tokenActingOn(east));
        assertEquals(east.getId().toString(), eastSummary.get("branchId"));
        assertEquals(1L, count(eastSummary, "patients"),
                "the east branch must see exactly its own patient");
        assertEquals(east.getCode(), eastSummary.get("branchCode"),
                "each summary names the branch its verified context selected");
    }

    /**
     * Network contract for the organization-scoped ADMIN context: exact key
     * declaration order, every active branch listed in deterministic code
     * order including all-zero summaries, organization totals equal to the
     * sum over the returned branch summaries, and a scoped write moving only
     * its own branch's entry — totals are server-derived, never client or
     * whole-table aggregation, and legacy unowned rows stay invisible.
     */
    @Test
    @Order(5)
    void networkReturnsDeterministicScopedSummariesForOrganizationAdmins() {
        String token = login(ADMIN_USER);
        Map<String, Object> before = readNetwork(token);
        assertEquals(NETWORK_KEY_ORDER, new ArrayList<>(before.keySet()),
                "the network summary must expose exactly the Task 10 keys in declaration order");
        List<Map<String, Object>> branchesBefore = branchList(before.get("branches"));
        List<String> codes = branchesBefore.stream()
                .map(branch -> String.valueOf(branch.get("branchCode"))).toList();
        assertEquals(codes.stream().sorted().toList(), codes,
                "the per-branch summaries must appear in deterministic code order");
        for (Map<String, Object> branch : branchesBefore) {
            assertEquals(BRANCH_KEY_ORDER, new ArrayList<>(branch.keySet()),
                    "every per-branch summary must carry the exact typed branch keys");
        }
        for (String key : NETWORK_KEY_ORDER.subList(2, NETWORK_KEY_ORDER.size() - 1)) {
            assertEquals(sumBranches(branchesBefore, key), count(before, key),
                    "the organization total '" + key + "' must equal the sum over the branch summaries");
        }

        Branch zeroBranch = createBranch("DASHBOARD-BR-ZERO-" + suffix);
        List<Map<String, Object>> withZero = branchList(readNetwork(token).get("branches"));
        Map<String, Object> zeroEntry = withZero.stream()
                .filter(branch -> String.valueOf(branch.get("branchId")).equals(zeroBranch.getId().toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a branch without records must still be listed"));
        for (String key : BRANCH_KEY_ORDER.subList(3, BRANCH_KEY_ORDER.size())) {
            assertEquals(0L, count(zeroEntry, key),
                    "the zero branch must honestly report '" + key + "' as 0");
        }

        Branch defaultBranch = defaultBranch();
        String branchToken = tokenActingOn(defaultBranch);
        patients.save(new Patient(defaultBranch, "MRN-DASH-NET-" + suffix, "Synthetic Network Patient",
                null, null, null, null, null, null));
        patients.save(new Patient(null, "MRN-DASH-NET-LEGACY-" + suffix, "Synthetic Network Legacy",
                null, null, null, null, null, null));

        Map<String, Object> after = readNetwork(token);
        assertEquals(count(before, "patients") + 1, count(after, "patients"),
                "the organization total must move exactly once: only the owned row counts");
        List<Map<String, Object>> branchesAfter = branchList(after.get("branches"));
        long defaultBefore = branchesBefore.stream()
                .filter(branch -> String.valueOf(branch.get("branchId")).equals(defaultBranch.getId().toString()))
                .findFirst().map(branch -> count(branch, "patients")).orElseThrow();
        long defaultAfter = branchesAfter.stream()
                .filter(branch -> String.valueOf(branch.get("branchId")).equals(defaultBranch.getId().toString()))
                .findFirst().map(branch -> count(branch, "patients")).orElseThrow();
        assertEquals(defaultBefore + 1, defaultAfter,
                "only the owning branch's summary must move; the legacy row stays invisible everywhere");
    }

    /**
     * Ordinary denial for every context that is not an enabled ADMIN with
     * ORGANIZATION scope: a branch-scoped BILLING account and an
     * organization-scoped NURSE account both receive the shared 403, and
     * anonymous requests stay unauthenticated with 401.
     */
    @Test
    @Order(6)
    void networkDeniesEveryNonOrganizationAdminContext() {
        assertEquals(HttpStatus.FORBIDDEN, rawGet("/api/dashboard/network", login(BILLING_USER)).getStatusCode(),
                "a branch-scoped context is denied the network view even with a non-ADMIN role");
        assertEquals(HttpStatus.FORBIDDEN, rawGet("/api/dashboard/network", login(NURSE_USER)).getStatusCode(),
                "an organization scope alone is not enough: the role must be ADMIN");
        assertEquals(HttpStatus.UNAUTHORIZED, rawGet("/api/dashboard/branch", null).getStatusCode(),
                "anonymous requests stay unauthenticated");
        assertEquals(HttpStatus.UNAUTHORIZED, rawGet("/api/dashboard/network", null).getStatusCode(),
                "anonymous requests never learn whether the network view exists");
    }

    /**
     * The retained alias is exactly the branch summary for the same acting
     * context — same keys, same order, same body — and seeding a legacy
     * row with no branch moves neither the alias nor the branch path nor
     * the network view: the alias never falls back to whole-table counts.
     */
    @Test
    @Order(7)
    void legacyAliasIsExactlyTheBranchSummaryAndNeverWholeTable() {
        String token = login(BILLING_USER);
        Map<String, Object> alias = readAlias(token);
        assertEquals(BRANCH_KEY_ORDER, new ArrayList<>(alias.keySet()),
                "the alias must answer with the typed branch summary, not the retired flat shape");
        assertEquals(readBranch(token), alias,
                "the alias must be exactly the branch summary for the same context");

        patients.save(new Patient(null, "MRN-DASH-ALIAS-LEGACY-" + suffix, "Synthetic Alias Legacy",
                null, null, null, null, null, null));

        assertEquals(alias, readAlias(token),
                "a legacy unowned row must not move the alias — it is branch-scoped, never whole-table");
        assertEquals(readBranch(token), readAlias(token),
                "the alias and the branch path keep answering identically");
    }

    // ------------------------------------------------------------------
    // Synthetic seeding (unique suffix keeps rows disposable)
    // ------------------------------------------------------------------

    private Branch defaultBranch() {
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseThrow();
        return branches.findByOrganizationIdAndCode(org.getId(), TEST_BRANCH_CODE).orElseThrow();
    }

    private Branch createBranch(String code) {
        HospitalOrganization org = organizations.findByCode(TEST_ORG_CODE).orElseThrow();
        return branches.save(new Branch(org, code, "Synthetic Branch " + code, "8 Zero Way"));
    }

    /** The exact synthetic cohort: whole-row totals plus one row per status bucket and bed state. */
    private void seedCohort(Branch branch) {
        Patient first = patients.save(new Patient(branch, "MRN-DASH-A-" + suffix, "Synthetic Cohort Patient A",
                null, null, null, null, null, null));
        patients.save(new Patient(branch, "MRN-DASH-B-" + suffix, "Synthetic Cohort Patient B",
                null, null, null, null, null, null));
        String firstId = first.getId().toString();

        seedAppointment(branch, "2038-02-01T09:00");
        seedAppointment(branch, "2038-02-02T09:00");
        seedAppointment(branch, "2038-02-03T09:00");
        seedAppointment(branch, TODAY + "T08:00");
        seedAppointment(branch, TODAY + "T18:30");
        seedAppointment(branch, "2037-01-03T23:59");
        seedAppointment(branch, "2037-01-05T00:00");
        seedAppointment(branch, TODAY + "T00:00:45");

        admissions.save(new Admission(branch.getId(), firstId, "2038-01-01T08:00", "synthetic open stay A"));
        admissions.save(new Admission(branch.getId(), firstId, "2038-01-02T08:00", "synthetic open stay B"));
        Admission discharged = new Admission(branch.getId(), firstId, "2038-01-03T08:00", "synthetic discharged stay");
        discharged.dischargeAt("2038-01-05T09:30");
        admissions.save(discharged);

        emergencyVisits.save(new EmergencyVisit(branch.getId(), firstId, "2038-01-01T09:00", "3",
                "synthetic waiting visit", "WAITING"));
        emergencyVisits.save(new EmergencyVisit(branch.getId(), firstId, "2038-01-02T09:00", "2",
                "synthetic waiting visit", "WAITING"));
        emergencyVisits.save(new EmergencyVisit(branch.getId(), firstId, "2038-01-03T09:00", "4",
                "synthetic treated visit", "IN_TREATMENT"));
        emergencyVisits.save(new EmergencyVisit(branch.getId(), firstId, "2038-01-04T09:00", "5",
                "synthetic closed visit", "CLOSED"));

        invoices.save(new Invoice(branch.getId(), firstId, "INV-DASH-DRAFT-" + suffix, "10.00", "USD", "DRAFT"));
        invoices.save(new Invoice(branch.getId(), firstId, "INV-DASH-ISSUED-" + suffix, "20.00", "USD", "ISSUED"));
        invoices.save(new Invoice(branch.getId(), firstId, "INV-DASH-PAID-" + suffix, "30.00", "USD", "PAID"));
        invoices.save(new Invoice(branch.getId(), firstId, "INV-DASH-VOID-" + suffix, "40.00", "USD", "VOID"));

        beds.save(new Bed(branch, "Ward A", "101", "A-01"));
        Bed occupied = beds.save(new Bed(branch, "Ward A", "102", "A-02"));
        occupied.markOccupiedByAdmission();
        beds.save(occupied);
        Bed maintenance = beds.save(new Bed(branch, "Ward A", "103", "A-03"));
        maintenance.changeOperationalStatus("MAINTENANCE");
        beds.save(maintenance);
        Bed outOfService = beds.save(new Bed(branch, "Ward A", "104", "A-04"));
        outOfService.changeOperationalStatus("MAINTENANCE");
        outOfService.changeOperationalStatus("OUT_OF_SERVICE");
        beds.save(outOfService);
    }

    /** Seeds one appointment in the canonical representation the service writes. */
    private void seedAppointment(Branch branch, String scheduledAt) {
        appointments.save(new Appointment(branch, "raw-cohort-patient-ref", "raw-cohort-professional-ref",
                scheduledAt, 30, java.time.LocalDateTime.parse(scheduledAt).plusMinutes(30).toString(),
                "Consultation", "scheduled"));
    }

    // ------------------------------------------------------------------
    // HTTP, audit, and assertion helpers
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

    /** Issues a replacement token acting exactly on one branch through the real context contract. */
    private String tokenActingOn(Branch branch) {
        UUID assignmentId = assignments
                .findByAccountIdAndEnabledTrueOrderByRoleAscScopeAscIdAsc(
                        accounts.findByUsername(ADMIN_USER).orElseThrow().getId())
                .stream().filter(assignment -> assignment.getRole() == Role.ADMIN
                        && assignment.getScope() == AssignmentScope.ORGANIZATION)
                .findFirst().orElseThrow().getId();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(login(ADMIN_USER));
        ResponseEntity<Map<String, Object>> switched = rest.exchange("/api/auth/context", HttpMethod.POST,
                new HttpEntity<>(Map.of("assignmentId", assignmentId.toString(),
                        "branchId", branch.getId().toString()), headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        assertEquals(HttpStatus.OK, switched.getStatusCode(),
                "the organization-scope ADMIN must act on any active branch of its organization");
        return String.valueOf(switched.getBody().get("accessToken"));
    }

    private ResponseEntity<Map<String, Object>> getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    private ResponseEntity<Map<String, Object>> rawGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    private Map<String, Object> readBranch(String token) {
        ResponseEntity<Map<String, Object>> response = getJson("/api/dashboard/branch", token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the branch summary must carry a body");
        return body;
    }

    private Map<String, Object> readAlias(String token) {
        ResponseEntity<Map<String, Object>> response = getJson("/api/dashboard", token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the alias must carry a body");
        return body;
    }

    private Map<String, Object> readNetwork(String token) {
        ResponseEntity<Map<String, Object>> response = getJson("/api/dashboard/network", token);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "the network summary must carry a body");
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> branchList(Object raw) {
        assertInstanceOf(List.class, raw, "the per-branch summaries must be a JSON array");
        return (List<Map<String, Object>>) raw;
    }

    private long sumBranches(List<Map<String, Object>> branchEntries, String key) {
        return branchEntries.stream().mapToLong(branch -> count(branch, key)).sum();
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

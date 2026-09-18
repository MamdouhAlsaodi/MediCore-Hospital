package com.mamtrex.hospital.bootstrap;

import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionBedAssignment;
import com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.audit.AuditEvent;
import com.mamtrex.hospital.audit.AuditEventRepository;
import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingAssignment;
import com.mamtrex.hospital.auth.ActingAssignmentRepository;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.billing.Invoice;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.emergency.EmergencyVisit;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.reporting.DashboardDtos;
import com.mamtrex.hospital.reporting.DashboardService;
import com.mamtrex.hospital.staff.StaffAvailability;
import com.mamtrex.hospital.staff.StaffAvailabilityRepository;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo seeding contract tests (docs/plan1.md Task 11, docs/plan2.md Task 8,
 * docs/plan3.md Tasks 2, 4, and 12).
 *
 * Pins the opt-in bootstrap behavior: the initializer does not exist by
 * default (no flag, no writes), the dedicated {@code medicore.demo.seed}
 * flag produces the coherent three-branch synthetic operations cohort —
 * one stable synthetic organization, three obviously synthetic branches of
 * varied size under stable business keys, branch-owned departments and
 * professionals, dated same-branch availability, beds in every operational
 * status, same-branch patients, appointments, admissions with live bed
 * assignments, emergency visits, and simulated invoices — every seeded
 * reference resolves same-branch, no branch-owned row has null or dangling
 * ownership, no bed/admission contradiction exists, every newly created
 * seeded record produces exactly one audit event attributed to the system
 * actor carrying the owning branch as its only context value (never a
 * fabricated assignment, role, or organization), the typed branch and
 * network dashboards count the cohort exactly with nonzero coverage per
 * branch, repeated initialization inserts zero records and zero events,
 * Pins the opt-in bootstrap behavior: the initializer does not exist by
 * default (no flag, no writes), the dedicated {@code medicore.demo.seed}
 * flag produces the coherent synthetic operations cohort — one stable
 * synthetic organization, three obviously synthetic hospitals (the legacy
 * facility plus the Phase 5 US1 fixture pair) with seven branches of varied
 * size under stable business keys, branch-owned departments and
 * professionals on the legacy cohort, dated same-branch availability, beds
 * in every operational status, same-branch patients, appointments,
 * admissions with live bed assignments, emergency visits, and simulated
 * invoices — every seeded reference resolves same-branch, no branch-owned
 * row has null or dangling ownership, no bed/admission contradiction
 * exists, every newly created seeded record produces exactly one audit
 * event attributed to the system actor carrying the owning branch as its
 * only context value (never a fabricated assignment, role, or
 * organization), the typed branch and network dashboards count the cohort
 * exactly with nonzero coverage per legacy branch, repeated initialization
 * inserts zero records and zero events, and the disabled default touches no
 * store and records no event. The
 * opt-in review-account bootstrap also pins the enabled acting assignments
 * of the cohort: ADMIN at ORGANIZATION scope and doctor/nurse at BRANCH
 * scope on the deterministic first-by-code demo branch. Runs against an
 * isolated in-memory H2 database (never the production file store) with
 * disposable synthetic test-only secrets; no real personal or clinical
 * data and no hardcoded credentials are involved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-data-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + DemoDataInitializerTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + DemoDataInitializerTest.TEST_ACCOUNT_PASSWORD,
        "medicore.review-accounts.enabled=true",
        "HOSPITAL_REVIEW_DOCTOR_PASSWORD=" + DemoDataInitializerTest.TEST_ACCOUNT_PASSWORD,
        "HOSPITAL_REVIEW_NURSE_PASSWORD=" + DemoDataInitializerTest.TEST_ACCOUNT_PASSWORD,
        "medicore.demo.seed=true"
})
class DemoDataInitializerTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-demo-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a real credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-demo-01";

    /** Stable demo business keys: one organization, three hospitals, seven synthetic branches. */
    private static final String DEMO_ORG_CODE = "DEMO-ORG-001";
    private static final String DEMO_LEGACY_HOSPITAL_CODE = "LEGACY-HOSPITAL-001";
    private static final String DEMO_HOSPITAL_RIVERSIDE_CODE = "DEMO-HOSP-002";
    private static final String DEMO_HOSPITAL_HARBORVIEW_CODE = "DEMO-HOSP-003";
    private static final Set<String> DEMO_HOSPITAL_CODES = Set.of(
            DEMO_LEGACY_HOSPITAL_CODE, DEMO_HOSPITAL_RIVERSIDE_CODE, DEMO_HOSPITAL_HARBORVIEW_CODE);
    private static final String DEMO_BRANCH_CODE = "DEMO-BR-001";
    private static final String DEMO_BRANCH_NORTH_CODE = "DEMO-BR-002";
    private static final String DEMO_BRANCH_HARBOR_CODE = "DEMO-BR-003";
    private static final String DEMO_BRANCH_RIVERSIDE_EAST_CODE = "DEMO-BR-1101";
    private static final String DEMO_BRANCH_RIVERSIDE_WEST_CODE = "DEMO-BR-1102";
    private static final String DEMO_BRANCH_HARBORVIEW_PIER_CODE = "DEMO-BR-2201";
    private static final String DEMO_BRANCH_HARBORVIEW_DUNE_CODE = "DEMO-BR-2202";
    private static final Set<String> DEMO_BRANCH_CODES = Set.of(
            DEMO_BRANCH_CODE, DEMO_BRANCH_NORTH_CODE, DEMO_BRANCH_HARBOR_CODE,
            DEMO_BRANCH_RIVERSIDE_EAST_CODE, DEMO_BRANCH_RIVERSIDE_WEST_CODE,
            DEMO_BRANCH_HARBORVIEW_PIER_CODE, DEMO_BRANCH_HARBORVIEW_DUNE_CODE);

    /** Deterministic per-hospital branch layout of the Phase 5 US1 fixture (T038). */
    private static final Map<String, List<String>> DEMO_BRANCHES_BY_HOSPITAL_CODE = Map.of(
            DEMO_LEGACY_HOSPITAL_CODE,
            List.of(DEMO_BRANCH_CODE, DEMO_BRANCH_NORTH_CODE, DEMO_BRANCH_HARBOR_CODE),
            DEMO_HOSPITAL_RIVERSIDE_CODE,
            List.of(DEMO_BRANCH_RIVERSIDE_EAST_CODE, DEMO_BRANCH_RIVERSIDE_WEST_CODE),
            DEMO_HOSPITAL_HARBORVIEW_CODE,
            List.of(DEMO_BRANCH_HARBORVIEW_PIER_CODE, DEMO_BRANCH_HARBORVIEW_DUNE_CODE));

    /** Branch-owned demo departments keyed per branch. */
    private static final Set<String> DEMO_DEPARTMENT_CODES =
            Set.of("DEMO-DEP-0001", "DEMO-DEP-0002", "DEMO-DEP-0101", "DEMO-DEP-0201");

    private static final Set<String> DEMO_MRNS = Set.of(
            "DEMO-0001", "DEMO-0002", "DEMO-0003", "DEMO-0004", "DEMO-0005", "DEMO-0006");
    private static final Set<String> DEMO_STAFF_CODES = Set.of(
            "DEMO-STAFF-001", "DEMO-STAFF-002", "DEMO-STAFF-0101", "DEMO-STAFF-0102", "DEMO-STAFF-0201");

    /** Exact first-run composition of the three-branch cohort (docs/plan3.md Task 12). */
    private static final long TOTAL_PATIENTS = 6L;
    private static final long TOTAL_STAFF = 5L;
    private static final long TOTAL_APPOINTMENTS = 4L;
    private static final long TOTAL_AVAILABILITY = 5L;
    private static final long TOTAL_BEDS = 8L;
    private static final long TOTAL_ADMISSIONS = 4L;
    private static final long TOTAL_EMERGENCY_VISITS = 5L;
    private static final long TOTAL_INVOICES = 7L;

    /**
     * Exact audit ledger of one full first run: one CREATE per newly created
     * row (1 organization + 3 hospitals (1 legacy + 2 Phase 5 US1 fixture
     * hospitals) + 7 branches (3 legacy + 4 US1 fixture branches) + 4
     * departments + 6 patients + 5 professionals + 4 appointments + 5
     * availability intervals + 8 beds + 4 admissions + 5 emergency visits +
     * 7 invoices = 59) plus the two admission-bed assignment actions
     * recorded with the {@code AdmissionService} UPDATE convention. A second
     * run adds zero.
     */
    private static final long FULL_AUDIT_LEDGER_SIZE = 61L;

    /** Meaningless demo triage labels (docs/plan2.md Task 8: never clinical advice). */
    private static final Set<String> DEMO_TRIAGE_LABELS = Set.of("1", "2", "3", "4", "5");

    @Autowired
    DemoDataInitializer initializer;

    @Autowired
    PatientRepository patients;

    @Autowired
    StaffMemberRepository staff;

    @Autowired
    StaffAvailabilityRepository availability;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    AdmissionRepository admissions;

    @Autowired
    AdmissionBedAssignmentRepository bedAssignments;

    @Autowired
    EmergencyVisitRepository emergencyVisits;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    BedRepository beds;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    DashboardService dashboardService;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    @Autowired
    HospitalFacilityRepository hospitals;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    ActingAssignmentRepository assignments;

    // ------------------------------------------------------------------
    // First-run composition.
    // ------------------------------------------------------------------

    /**
     * The flag creates exactly the named synthetic cohort at startup; this
     * test never calls the seeder itself, so a green run proves the runner
     * executed during context startup.
     */
    @Test
    void enabledFlagSeedsTheNamedSyntheticCohortAtStartup() {
        List<Patient> demoPatientRows = demoPatients();
        assertEquals(TOTAL_PATIENTS, demoPatientRows.size(),
                "the demo cohort must contain exactly six synthetic patients");
        assertEquals(DEMO_MRNS, demoPatientRows.stream()
                        .map(Patient::getMedicalRecordNumber)
                        .collect(Collectors.toSet()),
                "demo patients must carry the stable DEMO medical record numbers");
        assertTrue(demoPatientRows.stream().allMatch(p -> p.getFullName().startsWith("Demo Patient ")),
                "demo patient names must be obviously synthetic");
        assertTrue(demoPatientRows.stream().allMatch(p -> p.getEmail().endsWith("@synthetic.test")),
                "demo patient emails must stay on the unmistakable synthetic domain");

        List<StaffMember> demoStaffRows = demoStaff();
        assertEquals(TOTAL_STAFF, demoStaffRows.size(),
                "the demo cohort must contain exactly five synthetic professionals");
        assertEquals(DEMO_STAFF_CODES, demoStaffRows.stream()
                        .map(StaffMember::getEmployeeCode)
                        .collect(Collectors.toSet()),
                "demo professionals must carry the stable DEMO employee codes");
        assertTrue(demoStaffRows.stream().allMatch(s -> s.getFullName().startsWith("Demo ")),
                "professional names must be obviously synthetic");

        assertEquals(TOTAL_APPOINTMENTS, appointments.count(),
                "the demo cohort must contain exactly four synthetic appointments");
        assertEquals(TOTAL_AVAILABILITY, availability.count(),
                "the demo cohort must contain exactly five dated availability intervals");
        assertEquals(TOTAL_BEDS, beds.count(),
                "the demo cohort must contain exactly eight synthetic beds");
        assertEquals(TOTAL_ADMISSIONS, admissions.count(),
                "the demo cohort must contain exactly four synthetic admissions");
        assertEquals(TOTAL_EMERGENCY_VISITS, emergencyVisits.count(),
                "the demo cohort must contain exactly five synthetic emergency visits");
        assertEquals(TOTAL_INVOICES, invoices.count(),
                "the demo cohort must contain exactly seven simulated invoices");
    }

    /**
     * Phase 5 US1 hierarchy (T038/T039): exactly one synthetic organization
     * with exactly three obviously synthetic hospitals (the deterministic
     * legacy hospital plus two Phase 5 fixture hospitals across distinct
     * IANA zones) and seven synthetic branches — three under the legacy
     * hospital and exactly two under each fixture hospital — all active,
     * all bound to that organization under immutable business keys, plus
     * only the initializer-owned departments on the legacy-hospital
     * branches.
     */
    @Test
    void multiHospitalHierarchyIsSeededUnderStableBusinessKeys() {
        List<HospitalOrganization> orgs = organizations.findAll().stream().toList();
        assertEquals(1, orgs.size(), "the initializer must own exactly one organization");
        assertEquals(DEMO_ORG_CODE, orgs.get(0).getCode(), "the organization code must be the immutable demo key");
        assertTrue(orgs.get(0).getName().startsWith("Demo "), "the organization name must be obviously synthetic");

        // Phase 5 US1 (T038): exactly three deterministic hospitals — the
        // legacy facility of the Phase 4 cohort plus the US1 fixture pair.
        List<HospitalFacility> demoHospitals = hospitals.findAll().stream().toList();
        assertEquals(3, demoHospitals.size(), "the initializer must own exactly three hospitals");
        assertEquals(DEMO_HOSPITAL_CODES, demoHospitals.stream()
                        .map(HospitalFacility::getCode).collect(Collectors.toSet()),
                "the hospitals must carry exactly the stable deterministic business keys");
        assertTrue(demoHospitals.stream().allMatch(h -> orgs.get(0).getId().equals(h.getOrganization().getId())),
                "every demo hospital must belong to the demo organization");
        assertTrue(demoHospitals.stream().allMatch(HospitalFacility::isActive), "every demo hospital must be active");

        List<Branch> demoBranches = branches.findAll().stream().toList();
        assertEquals(7, demoBranches.size(), "the initializer must own exactly seven synthetic branches");
        assertEquals(DEMO_BRANCH_CODES, demoBranches.stream().map(Branch::getCode).collect(Collectors.toSet()),
                "the branches must carry exactly the stable DEMO-BR keys");
        assertTrue(demoBranches.stream().allMatch(Branch::isActive), "every demo branch must be active");
        assertTrue(demoBranches.stream().allMatch(b -> b.getName().startsWith("Demo ")),
                "branch names must be obviously synthetic");
        assertTrue(demoBranches.stream().allMatch(b -> !b.getLocationLabel().isBlank()),
                "every branch must carry a location label");
        assertTrue(demoBranches.stream().allMatch(b -> orgs.get(0).getId().equals(b.getOrganization().getId())),
                "every demo branch must belong to the demo organization");
        Map<String, List<String>> branchCodesByHospitalCode = new java.util.TreeMap<>();
        for (HospitalFacility hospital : demoHospitals) {
            branchCodesByHospitalCode.put(hospital.getCode(), demoBranches.stream()
                    .filter(b -> hospital.getId().equals(b.getHospital().getId()))
                    .map(Branch::getCode).sorted().toList());
        }
        assertEquals(DEMO_BRANCHES_BY_HOSPITAL_CODE, branchCodesByHospitalCode,
                "each hospital must own exactly its deterministic branch set "
                        + "(legacy cohort under the legacy hospital, two branches per fixture hospital)");

        // Varied size: the three branches own different numbers of rows.
        List<Department> ownedDepartments = departments.findAll().stream()
                .filter(department -> department.getBranch() != null)
                .toList();
        assertEquals(4, ownedDepartments.size(), "the initializer must own exactly four demo departments");
        assertEquals(DEMO_DEPARTMENT_CODES, ownedDepartments.stream()
                        .map(Department::getCode).collect(Collectors.toSet()),
                "owned departments must carry exactly the stable DEMO-DEP keys");
        Map<String, List<Department>> byBranchCode = ownedDepartments.stream().collect(
                Collectors.groupingBy(department -> demoBranches.stream()
                        .filter(b -> b.getId().equals(department.getBranch().getId()))
                        .findFirst().orElseThrow().getCode()));
        assertEquals(2, byBranchCode.get(DEMO_BRANCH_CODE).size(),
                "the default branch must own two departments");
        assertEquals(1, byBranchCode.get(DEMO_BRANCH_NORTH_CODE).size(),
                "the north branch must own one department");
        assertEquals(1, byBranchCode.get(DEMO_BRANCH_HARBOR_CODE).size(),
                "the harbor branch must own one department");
        assertTrue(ownedDepartments.stream().allMatch(d -> d.getName().startsWith("Demo ")),
                "demo department names must be obviously synthetic");

        // Varied size extends to the branch-owned workflow cohorts.
        Map<String, Long> patientsByBranchCode = patientsByBranch();
        assertEquals(3L, patientsByBranchCode.get(DEMO_BRANCH_CODE), "default branch: three patients");
        assertEquals(2L, patientsByBranchCode.get(DEMO_BRANCH_NORTH_CODE), "north branch: two patients");
        assertEquals(1L, patientsByBranchCode.get(DEMO_BRANCH_HARBOR_CODE), "harbor branch: one patient");
        Map<String, Long> staffByBranchCode = staffByBranch();
        assertEquals(2L, staffByBranchCode.get(DEMO_BRANCH_CODE), "default branch: two professionals");
        assertEquals(2L, staffByBranchCode.get(DEMO_BRANCH_NORTH_CODE), "north branch: two professionals");
        assertEquals(1L, staffByBranchCode.get(DEMO_BRANCH_HARBOR_CODE), "harbor branch: one professional");
        Map<String, Long> bedsByBranchCode = bedsByBranch();
        assertEquals(4L, bedsByBranchCode.get(DEMO_BRANCH_CODE), "default branch: four beds");
        assertEquals(2L, bedsByBranchCode.get(DEMO_BRANCH_NORTH_CODE), "north branch: two beds");
        assertEquals(2L, bedsByBranchCode.get(DEMO_BRANCH_HARBOR_CODE), "harbor branch: two beds");
    }

    /**
     * Phase 5 US1 fixture quality (T039): the fixture spans at least two
     * IANA zones (the demo cohort deliberately covers three — UTC,
     * America/New_York, Asia/Tokyo), every branch inherits a named IANA
     * zone, and the fixture is deterministic across restarts: two more
     * seeding runs insert zero hospitals/branches/events and leave every
     * hospital and branch business key and identity untouched.
     */
    @Test
    void phase5FixtureSpansSeveralZonesAndIsDeterministicAcrossRestart() {
        java.time.ZoneId UTC = java.time.ZoneId.of("UTC");
        java.time.ZoneId newYork = java.time.ZoneId.of("America/New_York");
        java.time.ZoneId tokyo = java.time.ZoneId.of("Asia/Tokyo");

        Map<String, HospitalFacility> hospitalByCode = hospitals.findAll().stream()
                .collect(Collectors.toMap(HospitalFacility::getCode, h -> h));
        assertEquals(Set.of(UTC, newYork, tokyo), hospitalByCode.values().stream()
                        .map(HospitalFacility::getTimeZone).collect(Collectors.toSet()),
                "the demo hospitals must span at least two IANA zones (fixture covers three)");

        Map<String, java.time.ZoneId> zoneByBranchCode = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getCode, Branch::getTimeZone));
        assertEquals(DEMO_BRANCH_CODES, zoneByBranchCode.keySet());
        assertEquals(UTC, zoneByBranchCode.get(DEMO_BRANCH_CODE));
        assertEquals(newYork, zoneByBranchCode.get(DEMO_BRANCH_NORTH_CODE));
        assertEquals(tokyo, zoneByBranchCode.get(DEMO_BRANCH_HARBOR_CODE));
        assertEquals(newYork, zoneByBranchCode.get(DEMO_BRANCH_RIVERSIDE_EAST_CODE));
        assertEquals(newYork, zoneByBranchCode.get(DEMO_BRANCH_RIVERSIDE_WEST_CODE));
        assertEquals(tokyo, zoneByBranchCode.get(DEMO_BRANCH_HARBORVIEW_PIER_CODE));
        assertEquals(tokyo, zoneByBranchCode.get(DEMO_BRANCH_HARBORVIEW_DUNE_CODE));

        // Deterministic across restart: identities and keys are stable, and
        // repeated seeding inserts nothing new anywhere.
        Map<String, UUID> branchIdByCode = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getCode, Branch::getId));
        Map<String, UUID> hospitalIdByCode = hospitalByCode.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getId()));
        Map<String, Long> countsBefore = Map.of(
                "hospitals", hospitals.count(),
                "branches", branches.count(),
                "auditEvents", auditEvents.count());

        initializer.seedDemoCohort();
        initializer.seedDemoCohort();

        assertEquals(countsBefore, Map.of(
                        "hospitals", hospitals.count(),
                        "branches", branches.count(),
                        "auditEvents", auditEvents.count()),
                "a restart re-seed must insert zero hospitals, branches, and events");
        assertEquals(hospitalIdByCode, hospitals.findAll().stream()
                        .collect(Collectors.toMap(HospitalFacility::getCode, HospitalFacility::getId)),
                "hospital business keys and identities must stay deterministic across restarts");
        assertEquals(branchIdByCode, branches.findAll().stream()
                        .collect(Collectors.toMap(Branch::getCode, Branch::getId)),
                "branch business keys and identities must stay deterministic across restarts");
    }

    // ------------------------------------------------------------------
    // Referential integrity: ownership and same-branch references.
    // ------------------------------------------------------------------

    /**
     * Task 12 invariant: no branch-owned demo row has null or dangling
     * branch ownership, and every seeded reference resolves to a seeded
     * record of the SAME branch. The three demo branch rows are the only
     * owners; every workflow, care-operation, bed, and availability row
     * resolves to one of them.
     */
    @Test
    void everyBranchOwnedDemoRowHasValidSameBranchOwnershipAndReferences() {
        Map<UUID, String> branchCodeById = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getCode));
        assertEquals(DEMO_BRANCH_CODES, Set.copyOf(branchCodeById.values()),
                "the only owners in the isolated demo store are the seven demo branches");

        for (Patient patient : patients.findAll()) {
            assertNotNull(patient.getBranch(), "seeded patient " + patient.getMedicalRecordNumber()
                    + " must be branch-owned");
            assertTrue(branchCodeById.containsKey(patient.getBranch().getId()),
                    "seeded patient ownership must resolve to a demo branch");
        }
        for (StaffMember member : demoStaff()) {
            assertNotNull(member.getBranch(), "seeded professional " + member.getEmployeeCode()
                    + " must be branch-owned");
            assertTrue(branchCodeById.containsKey(member.getBranch().getId()),
                    "seeded professional ownership must resolve to a demo branch");
            // The free-form department label must name a department of the same branch.
            assertTrue(departments.findAll().stream().anyMatch(d -> d.getBranch() != null
                            && d.getBranch().getId().equals(member.getBranch().getId())
                            && d.getName().equals(member.getDepartment())),
                    "professional " + member.getEmployeeCode() + " must name a same-branch department");
        }
        for (Appointment appointment : appointments.findAll()) {
            assertNotNull(appointment.getBranch(), "seeded appointment must be branch-owned");
            UUID branchId = appointment.getBranch().getId();
            assertTrue(branchCodeById.containsKey(branchId), "appointment ownership must resolve to a demo branch");
            Patient patient = patients.findById(UUID.fromString(appointment.getPatientId()))
                    .orElseThrow(() -> new AssertionError("appointment patientId must resolve"));
            StaffMember professional = staff.findById(UUID.fromString(appointment.getProfessionalId()))
                    .orElseThrow(() -> new AssertionError("appointment professionalId must resolve"));
            assertEquals(branchId, patient.getBranch().getId(), "appointment patient must be same-branch");
            assertEquals(branchId, professional.getBranch().getId(), "appointment professional must be same-branch");
            assertNotNull(appointment.getDurationMinutes(), "seeded appointments carry the bounded duration");
            assertNotNull(appointment.getEndsAt(), "seeded appointments carry the computed window end");
            assertNotNull(appointment.getScheduledAt(),
                    "seeded scheduledAt must stay the typed unambiguous instant");
        }
        for (Bed bed : beds.findAll()) {
            assertNotNull(bed.getBranch(), "seeded bed must be branch-owned");
            assertTrue(branchCodeById.containsKey(bed.getBranch().getId()),
                    "bed ownership must resolve to a demo branch");
        }
        for (Admission admission : admissions.findAll()) {
            assertNotNull(admission.getBranchId(), "seeded admission must be branch-owned");
            assertTrue(branchCodeById.containsKey(admission.getBranchId()),
                    "admission ownership must resolve to a demo branch");
            Patient patient = patients.findById(UUID.fromString(admission.getPatientId()))
                    .orElseThrow(() -> new AssertionError("admission patientId must resolve"));
            assertEquals(admission.getBranchId(), patient.getBranch().getId(),
                    "admission patient must be same-branch");
        }
        for (EmergencyVisit visit : emergencyVisits.findAll()) {
            assertNotNull(visit.getBranchId(), "seeded emergency visit must be branch-owned");
            assertTrue(branchCodeById.containsKey(visit.getBranchId()),
                    "emergency ownership must resolve to a demo branch");
            Patient patient = patients.findById(UUID.fromString(visit.getPatientId()))
                    .orElseThrow(() -> new AssertionError("emergency patientId must resolve"));
            assertEquals(visit.getBranchId(), patient.getBranch().getId(),
                    "emergency patient must be same-branch");
        }
        for (Invoice invoice : invoices.findAll()) {
            assertNotNull(invoice.getBranchId(), "seeded invoice must be branch-owned");
            assertTrue(branchCodeById.containsKey(invoice.getBranchId()),
                    "invoice ownership must resolve to a demo branch");
            Patient patient = patients.findById(UUID.fromString(invoice.getPatientId()))
                    .orElseThrow(() -> new AssertionError("invoice patientId must resolve"));
            assertEquals(invoice.getBranchId(), patient.getBranch().getId(),
                    "invoice patient must be same-branch");
        }
        for (StaffAvailability interval : availability.findAll()) {
            assertNotNull(interval.getBranchId(), "seeded availability must be branch-owned");
            assertTrue(branchCodeById.containsKey(interval.getBranchId()),
                    "availability ownership must resolve to a demo branch");
            StaffMember member = staff.findById(interval.getStaffMemberId())
                    .orElseThrow(() -> new AssertionError("availability staffMemberId must resolve"));
            assertEquals(interval.getBranchId(), member.getBranch().getId(),
                    "availability professional must be same-branch");
            assertTrue(interval.getStartsAt().isBefore(interval.getEndsAt()),
                    "availability intervals must be valid half-open [startsAt, endsAt) windows");
        }
    }

    /**
     * Task 12 invariant: no bed/admission contradiction exists. A bed is
     * OCCUPIED exactly when one live assignment row points at it; every live
     * assignment binds an open (ADMITTED) same-branch admission; discharged
     * admissions hold no bed; every non-occupied bed is free of assignments.
     */
    @Test
    void noBedAdmissionContradictionExists() {
        Map<UUID, Admission> admissionById = admissions.findAll().stream()
                .collect(Collectors.toMap(Admission::getId, a -> a));
        List<AdmissionBedAssignment> liveAssignments = bedAssignments.findAll().stream().toList();
        Map<UUID, List<AdmissionBedAssignment>> byBed = liveAssignments.stream()
                .collect(Collectors.groupingBy(AdmissionBedAssignment::getBedId));
        Map<UUID, List<AdmissionBedAssignment>> byAdmission = liveAssignments.stream()
                .collect(Collectors.groupingBy(AdmissionBedAssignment::getAdmissionId));

        for (Bed bed : beds.findAll()) {
            List<AdmissionBedAssignment> assignmentsForBed = byBed.getOrDefault(bed.getId(), List.of());
            if (bed.isOccupied()) {
                assertEquals(1, assignmentsForBed.size(),
                        "an OCCUPIED bed must hold exactly one live assignment: " + bed.getBedNumber());
                Admission admission = admissionById.get(assignmentsForBed.get(0).getAdmissionId());
                assertNotNull(admission, "the live assignment must reference a seeded admission");
                assertEquals(Admission.STATUS_ADMITTED, admission.getStatus(),
                        "a bed-occupying admission must be open (ADMITTED)");
                assertEquals(bed.getBranch().getId(), admission.getBranchId(),
                        "the occupying admission must belong to the bed's branch");
            } else {
                assertTrue(assignmentsForBed.isEmpty(),
                        "a non-occupied bed must hold no live assignment: " + bed.getBedNumber());
            }
        }
        for (Admission admission : admissions.findAll()) {
            if (Admission.STATUS_DISCHARGED.equals(admission.getStatus())) {
                assertNotNull(admission.getDischargedAt(),
                        "a DISCHARGED admission must carry its discharge timestamp");
                assertFalse(byAdmission.containsKey(admission.getId()),
                        "a discharged admission must hold no live bed assignment");
            } else {
                assertNull(admission.getDischargedAt(), "an open admission must have no discharge timestamp");
                assertTrue(byAdmission.containsKey(admission.getId()),
                        "every open seeded admission must occupy exactly one bed");
                assertEquals(1, byAdmission.get(admission.getId()).size(),
                        "an admission holds at most one live bed assignment");
            }
        }
        assertEquals(2, liveAssignments.size(), "exactly the two open admissions occupy a bed each");
    }

    // ------------------------------------------------------------------
    // Status coverage and dated availability.
    // ------------------------------------------------------------------

    /**
     * Task 12 composition: care-operation fixtures cover every lifecycle
     * state across the branches — admissions in ADMITTED and DISCHARGED,
     * emergency visits in WAITING, IN_TREATMENT, and CLOSED, and invoices in
     * DRAFT, ISSUED, PAID, and VOID — with obviously synthetic labels and
     * canonical display-only values.
     */
    @Test
    void careOperationFixturesCoverEveryLifecycleStatusAcrossBranches() {
        Map<String, List<Admission>> admissionsByStatus = admissions.findAll().stream()
                .collect(Collectors.groupingBy(Admission::getStatus));
        assertEquals(Set.of("ADMITTED", "DISCHARGED"), admissionsByStatus.keySet(),
                "admission fixtures must cover exactly the open and discharged lifecycle states");
        assertEquals(2, admissionsByStatus.get("ADMITTED").size(), "exactly two admissions must be ADMITTED");
        assertEquals(2, admissionsByStatus.get("DISCHARGED").size(), "exactly two admissions must be DISCHARGED");
        assertTrue(admissions.findAll().stream().allMatch(a -> a.getReason().startsWith("Demo ")),
                "admission reasons must be obviously synthetic demo workflow labels");

        Map<String, List<EmergencyVisit>> visitsByStatus = emergencyVisits.findAll().stream()
                .collect(Collectors.groupingBy(EmergencyVisit::getStatus));
        assertEquals(Set.of("WAITING", "IN_TREATMENT", "CLOSED"), visitsByStatus.keySet(),
                "emergency fixtures must cover both active states and the closed terminal state");
        assertEquals(2, visitsByStatus.get("WAITING").size(), "exactly two visits must be WAITING");
        assertEquals(1, visitsByStatus.get("IN_TREATMENT").size(), "exactly one visit must be IN_TREATMENT");
        assertEquals(2, visitsByStatus.get("CLOSED").size(), "exactly two visits must be CLOSED");
        assertTrue(emergencyVisits.findAll().stream().allMatch(v -> DEMO_TRIAGE_LABELS.contains(v.getTriageLevel())),
                "triage must stay the meaningless 1-5 demo label");
        assertTrue(emergencyVisits.findAll().stream().allMatch(v -> v.getChiefComplaint().startsWith("Demo ")),
                "emergency complaints must be obviously synthetic demo workflow labels");

        Map<String, List<Invoice>> invoicesByStatus = invoices.findAll().stream()
                .collect(Collectors.groupingBy(Invoice::getStatus));
        assertEquals(Set.of("DRAFT", "ISSUED", "PAID", "VOID"), invoicesByStatus.keySet(),
                "invoice fixtures must cover exactly the DRAFT, ISSUED, PAID, and VOID states");
        assertEquals(2, invoicesByStatus.get("DRAFT").size(), "exactly two invoices must be DRAFT");
        assertEquals(2, invoicesByStatus.get("ISSUED").size(), "exactly two invoices must be ISSUED");
        assertEquals(2, invoicesByStatus.get("PAID").size(), "exactly two invoices must be PAID");
        assertEquals(1, invoicesByStatus.get("VOID").size(), "exactly one invoice must be VOID");
        assertTrue(invoices.findAll().stream().allMatch(i -> i.getInvoiceNumber().startsWith("DEMO-INV-")),
                "invoice numbers must carry the obvious DEMO-INV- prefix");
        assertEquals(TOTAL_INVOICES, invoices.findAll().stream().map(Invoice::getInvoiceNumber).distinct().count(),
                "invoice numbers must stay unique stable keys");
        assertTrue(invoices.findAll().stream().allMatch(i -> i.getAmount().toPlainString().matches("\\d+\\.\\d{2}")),
                "invoice amounts must stay canonical display-only simulation values");
        assertTrue(invoices.findAll().stream().allMatch(i -> i.getCurrency().equals("USD")),
                "invoice currencies must stay display-only demo labels");
    }

    /**
     * Task 12: beds exist in all four operational statuses across the
     * branches, keyed by the stable (branch, ward, room, bedNumber) identity.
     */
    @Test
    void bedsCoverAllOperationalStatusesAcrossBranches() {
        Map<String, Long> byStatus = beds.findAll().stream()
                .collect(Collectors.groupingBy(Bed::getOccupancyStatus, Collectors.counting()));
        assertEquals(Set.of(Bed.STATUS_AVAILABLE, Bed.STATUS_OCCUPIED, Bed.STATUS_MAINTENANCE,
                        Bed.STATUS_OUT_OF_SERVICE), byStatus.keySet(),
                "bed fixtures must cover all four operational statuses");
        assertEquals(4L, byStatus.get(Bed.STATUS_AVAILABLE), "four beds must be AVAILABLE");
        assertEquals(2L, byStatus.get(Bed.STATUS_OCCUPIED), "two beds must be OCCUPIED");
        assertEquals(1L, byStatus.get(Bed.STATUS_MAINTENANCE), "one bed must be in MAINTENANCE");
        assertEquals(1L, byStatus.get(Bed.STATUS_OUT_OF_SERVICE), "one bed must be OUT_OF_SERVICE");
        assertEquals(TOTAL_BEDS, beds.findAll().stream()
                        .map(b -> b.getBranch().getId() + "/" + b.getWard() + "/" + b.getRoom() + "/" + b.getBedNumber())
                        .distinct().count(),
                "bed identities must stay unique per (branch, ward, room, bedNumber)");
    }

    /**
     * Task 12: dated same-branch availability intervals exist for the
     * professionals, they never overlap for the same professional, and every
     * seeded appointment window is contained in one interval of its own
     * professional in its own branch (the Task 9 contract the cohort must
     * demonstrate).
     */
    @Test
    void datedAvailabilityIsSameBranchConflictFreeAndCoversEveryAppointment() {
        List<StaffAvailability> intervals = availability.findAll().stream().toList();
        assertEquals(TOTAL_AVAILABILITY, intervals.size(), "exactly five dated intervals must be seeded");
        for (StaffAvailability interval : intervals) {
            StaffMember member = staff.findById(interval.getStaffMemberId())
                    .orElseThrow(() -> new AssertionError("availability staff must resolve"));
            assertEquals(interval.getBranchId(), member.getBranch().getId(),
                    "availability must belong to the professional's branch");
        }
        Map<UUID, List<StaffAvailability>> byStaff = intervals.stream()
                .collect(Collectors.groupingBy(StaffAvailability::getStaffMemberId));
        for (List<StaffAvailability> sameStaff : byStaff.values()) {
            for (int i = 0; i < sameStaff.size(); i++) {
                for (int j = i + 1; j < sameStaff.size(); j++) {
                    StaffAvailability a = sameStaff.get(i);
                    StaffAvailability b = sameStaff.get(j);
                    boolean overlaps = a.getStartsAt().isBefore(b.getEndsAt())
                            && b.getStartsAt().isBefore(a.getEndsAt());
                    assertFalse(overlaps, "seeded availability intervals must not overlap for one professional");
                }
            }
        }
        for (Appointment appointment : appointments.findAll()) {
            java.time.Instant start = appointment.getScheduledAt();
            java.time.Instant end = appointment.getEndsAt();
            StaffMember professional = staff.findById(UUID.fromString(appointment.getProfessionalId()))
                    .orElseThrow(() -> new AssertionError("appointment professional must resolve"));
            java.time.ZoneId zone = appointment.getBranch().getTimeZone();
            java.time.LocalDateTime windowStart = java.time.LocalDateTime.ofInstant(appointment.getScheduledAt(), zone);
            java.time.LocalDateTime windowEnd = java.time.LocalDateTime.ofInstant(appointment.getEndsAt(), zone);
            boolean contained = byStaff.getOrDefault(professional.getId(), List.of()).stream()
                    .filter(interval -> interval.getBranchId().equals(appointment.getBranch().getId()))
                    .anyMatch(interval -> !interval.getStartsAt().isAfter(windowStart)
                            && !interval.getEndsAt().isBefore(windowEnd));
            assertTrue(contained, "every seeded appointment must sit inside its professional's availability");
        }
    }

    // ------------------------------------------------------------------
    // Audit evidence: system actor, owning-branch context, exact ledger.
    // ------------------------------------------------------------------

    /**
     * Task 12 audit contract: seeding records exactly one event per seeded
     * action — a CREATE for every newly created row plus the two admission
     * bed-assignment actions recorded with the AdmissionService UPDATE
     * convention. Every event stays attributed to the {@code system} actor
     * and is context-aware without fabrication: each event tied to a
     * branch-owned resource carries its owning organization and branch ids.
     * Assignment, role, scope, and department stay null because no acting
     * assignment exists at startup; the organization's own event keeps the
     * fully context-less legacy shape. No event ever carries a correlation id
     * (no request boundary).
     */
    @Test
    void seededActionsProduceContextAwareSystemActorAuditEvents() {
        List<AuditEvent> events = auditEvents.findAll().stream().toList();
        assertEquals(FULL_AUDIT_LEDGER_SIZE, events.size(),
                "seeding must record exactly one audit event per seeded action "
                        + "(59 row inserts plus 2 bed-assignment actions)");
        assertTrue(events.stream().allMatch(e -> "system".equals(e.getActor())),
                "startup seeding has no authenticated user, so every actor must be system");
        assertEquals(59, events.stream().filter(e -> "CREATE".equals(e.getAction())).count(),
                "every newly created row must produce exactly one CREATE event");
        assertEquals(2, events.stream().filter(e -> "UPDATE".equals(e.getAction())).count(),
                "exactly the two bed-assignment actions produce UPDATE events");
        assertTrue(events.stream().allMatch(e -> e.getCorrelationId() == null),
                "startup events have no request boundary, so no correlation id may appear");
        assertTrue(events.stream().allMatch(e -> e.getAssignmentId() == null && e.getRole() == null
                        && e.getScope() == null && e.getDepartmentId() == null),
                "startup events must never fabricate an assignment, role, scope, or department");

        // Context-awareness: every event tied to a branch-owned seeded row
        // carries the owning organization and branch; the organization event
        // alone keeps the context-less legacy shape.
        Map<String, List<AuditEvent>> byType = events.stream()
                .collect(Collectors.groupingBy(AuditEvent::getResourceType));
        assertEquals(Set.of("Patient", "StaffMember", "Appointment", "StaffAvailability", "Admission",
                        "EmergencyVisit", "Invoice", "Bed", "HospitalOrganization", "HospitalFacility",
                        "Branch", "Department"),
                byType.keySet(), "event resource types must follow the conventions the services use");
        UUID organizationId = organizations.findByCode(DEMO_ORG_CODE).orElseThrow().getId();
        Set<UUID> branchIds = branches.findAll().stream().map(Branch::getId).collect(Collectors.toSet());
        assertTrue(events.stream().filter(e -> e.getBranchId() != null)
                        .allMatch(e -> organizationId.equals(e.getOrganizationId())),
                "every branch-attributed seed event must carry its owning organization");
        // Phase 5 (T026): every branch-attributed seed event also carries the
        // acting hospital derived from the owning branch — bounded context,
        // never guessed, never a fabricated assignment.
        Set<UUID> hospitalIds = branches.findAll().stream()
                .map(b -> b.getHospital().getId()).collect(Collectors.toSet());
        assertEquals(3, hospitalIds.size(),
                "the demo cohort must span exactly the three deterministic demo hospitals");
        assertTrue(events.stream().filter(e -> e.getBranchId() != null)
                        .allMatch(e -> e.getHospitalId() != null && hospitalIds.contains(e.getHospitalId())),
                "every branch-attributed seed event must carry its acting hospital");
        for (Map.Entry<String, List<AuditEvent>> entry : byType.entrySet()) {
            String type = entry.getKey();
            List<AuditEvent> typed = entry.getValue();
            switch (type) {
                case "HospitalOrganization" -> {
                    assertEquals(1, typed.size());
                    assertNull(typed.get(0).getBranchId(),
                            "the organization row has no owning branch, so its event stays context-less");
                }
                case "HospitalFacility" -> {
                    assertEquals(3, typed.size(),
                            "the legacy hospital plus the two Phase 5 US1 fixture hospitals");
                    assertTrue(typed.stream().allMatch(e -> e.getBranchId() == null),
                            "a hospital row has no owning branch, so its event stays context-less");
                }
                case "Branch" -> {
                    assertEquals(7, typed.size());
                    assertTrue(typed.stream().allMatch(e -> e.getBranchId() != null
                                    && branchIds.contains(e.getBranchId())
                                    && e.getBranchId().toString().equals(e.getResourceId())),
                            "a branch event attributes the event to the branch the row IS");
                }
                case "Department" -> {
                    assertEquals(4, typed.size());
                    assertBranchContextResolves(typed, branchIds, departments.findAll().stream()
                            .filter(d -> d.getBranch() != null)
                            .collect(Collectors.toMap(d -> d.getId().toString(), d -> d.getBranch().getId())));
                }
                case "Patient" -> {
                    assertEquals(6, typed.size());
                    assertBranchContextResolves(typed, branchIds, patients.findAll().stream()
                            .collect(Collectors.toMap(p -> p.getId().toString(), p -> p.getBranch().getId())));
                }
                case "StaffMember" -> {
                    assertEquals(5, typed.size());
                    assertBranchContextResolves(typed, branchIds, demoStaff().stream()
                            .collect(Collectors.toMap(s -> s.getId().toString(), s -> s.getBranch().getId())));
                }
                case "Appointment" -> {
                    assertEquals(4, typed.size());
                    assertBranchContextResolves(typed, branchIds, appointments.findAll().stream()
                            .collect(Collectors.toMap(a -> a.getId().toString(), a -> a.getBranch().getId())));
                }
                case "StaffAvailability" -> {
                    assertEquals(5, typed.size());
                    assertBranchContextResolves(typed, branchIds, availability.findAll().stream()
                            .collect(Collectors.toMap(a -> a.getId().toString(), StaffAvailability::getBranchId)));
                }
                case "Bed" -> {
                    assertEquals(8, typed.size());
                    assertBranchContextResolves(typed, branchIds, beds.findAll().stream()
                            .collect(Collectors.toMap(b -> b.getId().toString(), b -> b.getBranch().getId())));
                }
                case "Admission" -> {
                    assertEquals(6, typed.size(),
                            "four admission inserts plus two bed-assignment UPDATE actions");
                    assertBranchContextResolves(typed, branchIds, admissions.findAll().stream()
                            .collect(Collectors.toMap(a -> a.getId().toString(), Admission::getBranchId)));
                    assertEquals(2, typed.stream().filter(e -> "UPDATE".equals(e.getAction())
                                    && e.getDetails().startsWith("bed: ")).count(),
                            "assignment actions must follow the AdmissionService 'bed: <id>' convention");
                }
                case "EmergencyVisit" -> {
                    assertEquals(5, typed.size());
                    assertBranchContextResolves(typed, branchIds, emergencyVisits.findAll().stream()
                            .collect(Collectors.toMap(v -> v.getId().toString(), EmergencyVisit::getBranchId)));
                }
                case "Invoice" -> {
                    assertEquals(7, typed.size());
                    assertBranchContextResolves(typed, branchIds, invoices.findAll().stream()
                            .collect(Collectors.toMap(i -> i.getId().toString(), Invoice::getBranchId)));
                }
                default -> fail("unexpected resource type " + type);
            }
        }
    }

    private void assertBranchContextResolves(List<AuditEvent> events, Set<UUID> branchIds,
                                             Map<String, UUID> owningBranchByResourceId) {
        for (AuditEvent event : events) {
            assertNotNull(event.getBranchId(), "the event must carry its owning branch context");
            assertTrue(branchIds.contains(event.getBranchId()), "the branch context must resolve to a demo branch");
            assertEquals(owningBranchByResourceId.get(event.getResourceId()), event.getBranchId(),
                    "the event context must be exactly the owning branch of the resource it describes");
        }
    }

    // ------------------------------------------------------------------
    // Dashboard coverage through the typed Task 10 contracts.
    // ------------------------------------------------------------------

    /**
     * Task 12 dashboard coverage: every demo branch summary counts its
     * cohort slice exactly with nonzero core coverage, and the network
     * summary of an organization-scoped ADMIN equals the exact per-branch
     * sum with every status bucket populated. The acting contexts below are
     * fabricated principal shapes only — pointing at real server-owned demo
     * rows, exactly the values the JWT filter derives for such assignments.
     */
    @Test
    void dashboardsCountTheCohortExactlyWithNonzeroCoveragePerBranch() {
        HospitalOrganization organization = organizations.findByCode(DEMO_ORG_CODE).orElseThrow();
        Map<String, Branch> branchByCode = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getCode, b -> b));

        DashboardDtos.BranchSummary main = branchSummary(branchByCode.get(DEMO_BRANCH_CODE));
        assertEquals(3L, main.patients());
        assertEquals(2L, main.appointments());
        assertEquals(2L, main.admissions());
        assertEquals(1L, main.openAdmissions(), "the DISCHARGED admission must be excluded");
        assertEquals(3L, main.emergencyVisits());
        assertEquals(2L, main.activeEmergencyVisits(), "WAITING + IN_TREATMENT only; CLOSED excluded");
        assertEquals(4L, main.invoices());
        assertEquals(1L, main.invoicesDraft());
        assertEquals(1L, main.invoicesIssued());
        assertEquals(1L, main.invoicesPaid());
        assertEquals(1L, main.invoicesVoid());
        assertEquals(1L, main.bedsAvailable());
        assertEquals(1L, main.bedsOccupied());
        assertEquals(1L, main.bedsMaintenance());
        assertEquals(1L, main.bedsOutOfService());
        assertEquals(0L, main.todayAppointments(),
                "todayAppointments must stay honest: the demo fixtures are dated 2031, never the server's today");

        DashboardDtos.BranchSummary north = branchSummary(branchByCode.get(DEMO_BRANCH_NORTH_CODE));
        assertEquals(2L, north.patients());
        assertEquals(1L, north.appointments());
        assertEquals(1L, north.admissions());
        assertEquals(1L, north.openAdmissions());
        assertEquals(1L, north.emergencyVisits());
        assertEquals(1L, north.activeEmergencyVisits());
        assertEquals(2L, north.invoices());
        assertEquals(1L, north.bedsAvailable());
        assertEquals(1L, north.bedsOccupied());
        assertEquals(0L, north.bedsMaintenance());
        assertEquals(0L, north.bedsOutOfService());

        DashboardDtos.BranchSummary harbor = branchSummary(branchByCode.get(DEMO_BRANCH_HARBOR_CODE));
        assertEquals(1L, harbor.patients());
        assertEquals(1L, harbor.appointments());
        assertEquals(1L, harbor.admissions());
        assertEquals(0L, harbor.openAdmissions(), "the harbor admission is discharged");
        assertEquals(1L, harbor.emergencyVisits());
        assertEquals(0L, harbor.activeEmergencyVisits(), "the harbor visit is CLOSED");
        assertEquals(1L, harbor.invoices());
        assertEquals(2L, harbor.bedsAvailable());
        assertEquals(0L, harbor.bedsOccupied());

        DashboardDtos.NetworkSummary network = networkSummary(organization.getId());
        assertEquals(organization.getId(), network.organizationId());
        assertEquals(7, network.branches().size(),
                "the network view must compare all seven branches of the three hospitals");
        assertEquals(List.of(DEMO_BRANCH_CODE, DEMO_BRANCH_NORTH_CODE, DEMO_BRANCH_HARBOR_CODE,
                        DEMO_BRANCH_RIVERSIDE_EAST_CODE, DEMO_BRANCH_RIVERSIDE_WEST_CODE,
                        DEMO_BRANCH_HARBORVIEW_PIER_CODE, DEMO_BRANCH_HARBORVIEW_DUNE_CODE),
                network.branches().stream().map(DashboardDtos.BranchSummary::branchCode).toList(),
                "branch summaries must appear in deterministic code order");
        for (String emptyBranchCode : List.of(DEMO_BRANCH_RIVERSIDE_EAST_CODE, DEMO_BRANCH_RIVERSIDE_WEST_CODE,
                DEMO_BRANCH_HARBORVIEW_PIER_CODE, DEMO_BRANCH_HARBORVIEW_DUNE_CODE)) {
            DashboardDtos.BranchSummary fixtureBranch = network.branches().stream()
                    .filter(b -> b.branchCode().equals(emptyBranchCode)).findFirst().orElseThrow();
            assertEquals(0L, fixtureBranch.patients(),
                    "a Phase 5 fixture branch without a workflow cohort must stay an honest zero");
            assertEquals(0L, fixtureBranch.admissions());
            assertEquals(0L, fixtureBranch.bedsAvailable());
        }
        assertEquals(6L, network.patients());
        assertEquals(4L, network.appointments());
        assertEquals(4L, network.admissions());
        assertEquals(2L, network.openAdmissions());
        assertEquals(5L, network.emergencyVisits());
        assertEquals(3L, network.activeEmergencyVisits());
        assertEquals(7L, network.invoices());
        assertEquals(2L, network.invoicesDraft());
        assertEquals(2L, network.invoicesIssued());
        assertEquals(2L, network.invoicesPaid());
        assertEquals(1L, network.invoicesVoid());
        assertEquals(4L, network.bedsAvailable());
        assertEquals(2L, network.bedsOccupied());
        assertEquals(1L, network.bedsMaintenance());
        assertEquals(1L, network.bedsOutOfService());
        assertEquals(0L, network.todayAppointments());
        assertEquals(network.branches().stream().mapToLong(DashboardDtos.BranchSummary::patients).sum(),
                network.patients(), "organization totals must equal the sum over the branch summaries");
    }

    private DashboardDtos.BranchSummary branchSummary(Branch branch) {
        ActingContext actingContext = new ActingContext("system-dashboard-reader",
                UUID.randomUUID(), Role.ADMIN, AssignmentScope.BRANCH,
                branch.getOrganization().getId(), branch.getHospital().getId(), branch.getId(), null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actingContext, null, List.of()));
        try {
            return dashboardService.branchSummary();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private DashboardDtos.NetworkSummary networkSummary(UUID organizationId) {
        Branch readerBranch = branches.findAll().stream().findFirst().orElseThrow();
        ActingContext actingContext = new ActingContext("system-network-reader",
                UUID.randomUUID(), Role.ADMIN, AssignmentScope.ORGANIZATION, organizationId,
                readerBranch.getHospital().getId(), readerBranch.getId(), null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actingContext, null, List.of()));
        try {
            return dashboardService.networkSummary();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------
    // Enabled acting assignments of the cohort (DevAdminInitializer).
    // ------------------------------------------------------------------

    /**
     * Task 12: with the review-account bootstrap enabled, the cohort's
     * acting assignments are enabled and deterministic — ADMIN at
     * ORGANIZATION scope and doctor/nurse at BRANCH scope on the stable
     * default branch (the first active branch in code order even with three
     * branches present). Provisioning rides the ready event, so the demo
     * hierarchy exists by the time these rows are created.
     */
    @Test
    void cohortActingAssignmentsAreEnabledAndDeterministicallyBound() {
        HospitalOrganization organization = organizations.findByCode(DEMO_ORG_CODE).orElseThrow();
        HospitalFacility fixtureHospital = hospitals.findByOrganizationIdAndCode(
                organization.getId(), "LEGACY-HOSPITAL-001").orElseThrow();
        Branch defaultBranch = branches.findByHospitalIdAndCode(fixtureHospital.getId(), DEMO_BRANCH_CODE)
                .orElseThrow();

        List<ActingAssignment> seeded = assignments.findAll().stream().toList();
        assertEquals(3, seeded.size(), "admin, doctor, and nurse each hold exactly one bootstrap assignment");
        assertTrue(seeded.stream().allMatch(ActingAssignment::isEnabled),
                "every bootstrap assignment must be enabled");
        assertTrue(seeded.stream().allMatch(a -> organization.getId().equals(a.getOrganization().getId())),
                "every bootstrap assignment must belong to the demo organization");

        ActingAssignment admin = seeded.stream()
                .filter(a -> Role.ADMIN.equals(a.getRole())).findFirst().orElseThrow();
        assertEquals(AssignmentScope.ORGANIZATION, admin.getScope());
        assertNull(admin.getBranch(), "the ADMIN bootstrap assignment is organization-scoped with no branch");
        assertNull(admin.getDepartment());

        for (String username : List.of("doctor", "nurse")) {
            ActingAssignment review = seeded.stream()
                    .filter(a -> a.getAccount().getUsername().equals(username)).findFirst().orElseThrow();
            assertEquals(AssignmentScope.BRANCH, review.getScope());
            assertEquals(defaultBranch.getId(), review.getBranch().getId(),
                    username + " must act on the deterministic first-by-code demo branch");
            assertNull(review.getDepartment());
        }
    }

    // ------------------------------------------------------------------
    // Idempotency and the legacy seam.
    // ------------------------------------------------------------------

    /**
     * Task 2 legacy seam: an unknown pre-existing null-branch department is
     * never mass-updated. After two more seeding runs it stays untouched
     * and unassigned, while the demo hierarchy counts stay stable.
     */
    @Test
    void unknownNullBranchDepartmentsStayUntouchedAndRemainUnassigned() {
        String legacyCode = "LEGACY-UNKNOWN-" + UUID.randomUUID().toString().substring(0, 8);
        Department legacy = departments.save(new Department(null, legacyCode, "Unknown Legacy Row", "s", "l"));
        assertNull(legacy.getBranch(), "the fabricated legacy row must start unassigned");

        initializer.seedDemoCohort();
        initializer.seedDemoCohort();

        Department reloaded = departments.findById(legacy.getId()).orElseThrow();
        assertEquals(legacyCode, reloaded.getCode(), "the unknown row must stay untouched by seeding");
        assertNull(reloaded.getBranch(), "the unknown row must remain unassigned after reruns");
        assertEquals(4, departments.findAll().stream()
                        .filter(department -> department.getBranch() != null).count(),
                "reruns must not assign or create additional owned departments");
        assertEquals(1, organizations.count(), "reruns must not inflate organizations");
        assertEquals(7, branches.count(), "reruns must not inflate branches");
    }

    /**
     * Task 12 idempotency: a second run inserts zero records and zero
     * events across every seeded store, and the stable business keys stay
     * unchanged.
     */
    @Test
    void repeatedInitializationInsertsZeroRecordsAndZeroEvents() {
        Map<String, Long> before = storeCounts();
        assertEquals(Map.ofEntries(
                        Map.entry("patients", TOTAL_PATIENTS),
                        Map.entry("staff", TOTAL_STAFF),
                        Map.entry("appointments", TOTAL_APPOINTMENTS),
                        Map.entry("availability", TOTAL_AVAILABILITY),
                        Map.entry("beds", TOTAL_BEDS),
                        Map.entry("admissions", TOTAL_ADMISSIONS),
                        Map.entry("bedAssignments", 2L),
                        Map.entry("emergencyVisits", TOTAL_EMERGENCY_VISITS),
                        Map.entry("invoices", TOTAL_INVOICES),
                        Map.entry("organizations", 1L),
                        Map.entry("branches", 7L),
                        Map.entry("departments", 4L),
                        Map.entry("auditEvents", FULL_AUDIT_LEDGER_SIZE)),
                before, "the startup run must have produced the exact first-run composition");

        initializer.seedDemoCohort();
        initializer.seedDemoCohort();

        assertEquals(before, storeCounts(),
                "a repeated seed must insert zero records and zero events everywhere");
        assertEquals(DEMO_MRNS, demoPatients().stream()
                        .map(Patient::getMedicalRecordNumber)
                        .collect(Collectors.toSet()),
                "the stable patient keys must stay unchanged after a repeated seed");
        assertEquals(DEMO_STAFF_CODES, demoStaff().stream()
                        .map(StaffMember::getEmployeeCode)
                        .collect(Collectors.toSet()),
                "the stable professional keys must stay unchanged after a repeated seed");
        assertEquals(DEMO_BRANCH_CODES, branches.findAll().stream()
                        .map(Branch::getCode).collect(Collectors.toSet()),
                "the stable branch keys must stay unchanged after a repeated seed");
        assertEquals(DEMO_DEPARTMENT_CODES, departments.findAll().stream()
                        .filter(d -> d.getBranch() != null)
                        .map(Department::getCode).collect(Collectors.toSet()),
                "the stable department keys must stay unchanged after a repeated seed");
        assertEquals(4, invoices.findAll().stream().map(Invoice::getInvoiceNumber)
                        .filter(n -> n.startsWith("DEMO-INV-01")).count(),
                "the stable default-branch invoice numbers must stay unchanged after a repeated seed");
    }

    private Map<String, Long> storeCounts() {
        return Map.ofEntries(
                Map.entry("patients", patients.count()),
                Map.entry("staff", staff.count()),
                Map.entry("appointments", appointments.count()),
                Map.entry("availability", availability.count()),
                Map.entry("beds", beds.count()),
                Map.entry("admissions", admissions.count()),
                Map.entry("bedAssignments", bedAssignments.count()),
                Map.entry("emergencyVisits", emergencyVisits.count()),
                Map.entry("invoices", invoices.count()),
                Map.entry("organizations", organizations.count()),
                Map.entry("branches", branches.count()),
                Map.entry("departments", departments.count()),
                Map.entry("auditEvents", auditEvents.count()));
    }

    /**
     * Opt-in gate: without {@code medicore.demo.seed=true} the initializer
     * bean never exists and no store is touched — zero records are created,
     * zero care-operation rows, zero audit events.
     */
    @Test
    void initializerIsDisabledByDefaultAndNeverTouchesStores() {
        PatientRepository patientRepository = Mockito.mock(PatientRepository.class);
        StaffMemberRepository staffMemberRepository = Mockito.mock(StaffMemberRepository.class);
        StaffAvailabilityRepository staffAvailabilityRepository = Mockito.mock(StaffAvailabilityRepository.class);
        AppointmentRepository appointmentRepository = Mockito.mock(AppointmentRepository.class);
        AdmissionRepository admissionRepository = Mockito.mock(AdmissionRepository.class);
        AdmissionBedAssignmentRepository admissionBedAssignmentRepository =
                Mockito.mock(AdmissionBedAssignmentRepository.class);
        EmergencyVisitRepository emergencyVisitRepository = Mockito.mock(EmergencyVisitRepository.class);
        InvoiceRepository invoiceRepository = Mockito.mock(InvoiceRepository.class);
        BedRepository bedRepository = Mockito.mock(BedRepository.class);
        HospitalOrganizationRepository organizationRepository = Mockito.mock(HospitalOrganizationRepository.class);
        BranchRepository branchRepository = Mockito.mock(BranchRepository.class);
        DepartmentRepository departmentRepository = Mockito.mock(DepartmentRepository.class);
        AuditService auditService = Mockito.mock(AuditService.class);
        new ApplicationContextRunner()
                .withUserConfiguration(DemoDataInitializer.class)
                .withBean("patientRepository", PatientRepository.class, () -> patientRepository)
                .withBean("staffMemberRepository", StaffMemberRepository.class, () -> staffMemberRepository)
                .withBean("staffAvailabilityRepository", StaffAvailabilityRepository.class,
                        () -> staffAvailabilityRepository)
                .withBean("appointmentRepository", AppointmentRepository.class, () -> appointmentRepository)
                .withBean("admissionRepository", AdmissionRepository.class, () -> admissionRepository)
                .withBean("admissionBedAssignmentRepository", AdmissionBedAssignmentRepository.class,
                        () -> admissionBedAssignmentRepository)
                .withBean("emergencyVisitRepository", EmergencyVisitRepository.class,
                        () -> emergencyVisitRepository)
                .withBean("invoiceRepository", InvoiceRepository.class, () -> invoiceRepository)
                .withBean("bedRepository", BedRepository.class, () -> bedRepository)
                .withBean("organizationRepository", HospitalOrganizationRepository.class,
                        () -> organizationRepository)
                .withBean("branchRepository", BranchRepository.class, () -> branchRepository)
                .withBean("departmentRepository", DepartmentRepository.class, () -> departmentRepository)
                .withBean("auditService", AuditService.class, () -> auditService)
                .run(context -> {
                    assertTrue(context.getBeansOfType(DemoDataInitializer.class).isEmpty(),
                            "the initializer bean must not exist when the demo flag is absent (default off)");
                    Mockito.verifyNoInteractions(patientRepository, staffMemberRepository,
                            staffAvailabilityRepository, appointmentRepository, admissionRepository,
                            admissionBedAssignmentRepository, emergencyVisitRepository, invoiceRepository,
                            bedRepository, organizationRepository, branchRepository, departmentRepository,
                            auditService);
                });
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private Map<String, Long> patientsByBranch() {
        Map<UUID, String> codeById = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getCode));
        return patients.findAll().stream()
                .filter(p -> p.getBranch() != null)
                .collect(Collectors.groupingBy(p -> codeById.get(p.getBranch().getId()), Collectors.counting()));
    }

    private Map<String, Long> staffByBranch() {
        Map<UUID, String> codeById = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getCode));
        return demoStaff().stream()
                .filter(s -> s.getBranch() != null)
                .collect(Collectors.groupingBy(s -> codeById.get(s.getBranch().getId()), Collectors.counting()));
    }

    private Map<String, Long> bedsByBranch() {
        Map<UUID, String> codeById = branches.findAll().stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getCode));
        return beds.findAll().stream()
                .filter(b -> b.getBranch() != null)
                .collect(Collectors.groupingBy(b -> codeById.get(b.getBranch().getId()), Collectors.counting()));
    }

    private List<Patient> demoPatients() {
        return patients.findAll().stream()
                .filter(p -> p.getMedicalRecordNumber().startsWith("DEMO-"))
                .collect(Collectors.toList());
    }

    private List<StaffMember> demoStaff() {
        return staff.findAll().stream()
                .filter(s -> s.getEmployeeCode() != null && s.getEmployeeCode().startsWith("DEMO-STAFF-"))
                .collect(Collectors.toList());
    }
}

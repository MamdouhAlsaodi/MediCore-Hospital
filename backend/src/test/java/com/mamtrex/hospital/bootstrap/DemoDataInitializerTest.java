package com.mamtrex.hospital.bootstrap;

import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.audit.AuditEvent;
import com.mamtrex.hospital.audit.AuditEventRepository;
import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.billing.Invoice;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.department.DepartmentRepository;
import com.mamtrex.hospital.emergency.EmergencyVisit;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.reporting.DashboardService;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo seeding contract tests (docs/plan1.md Task 11, docs/plan2.md Task 8,
 * docs/plan3.md Task 2).
 *
 * Pins the opt-in bootstrap behavior: the initializer does not exist by
 * default (no flag, no writes), the dedicated {@code medicore.demo.seed}
 * flag produces a coherent, obviously synthetic cohort including the Task 2
 * hierarchy — one stable synthetic organization, one stable default branch,
 * and only the initializer-owned demo departments assigned to that branch —
 * every seeded reference resolves to a seeded record, repeated
 * initialization is idempotent without duplicate inflation or destructive
 * resets, every newly created seeded record produces exactly one CREATE
 * audit event attributed to the system actor (reused records add none),
 * unknown pre-existing null-branch departments stay untouched and
 * unassigned, the dashboard aggregates reflect the exact fixture
 * composition through the real {@link DashboardService}, and the disabled
 * default touches no store and records no event. Runs against an isolated
 * in-memory H2 database (never the production file store) with disposable
 * synthetic test-only secrets; no real personal or clinical data and no
 * hardcoded credentials are involved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-data-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + DemoDataInitializerTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + DemoDataInitializerTest.TEST_ACCOUNT_PASSWORD,
        "medicore.demo.seed=true"
})
class DemoDataInitializerTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-demo-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-demo-01";

    private static final Set<String> DEMO_MRNS = Set.of("DEMO-0001", "DEMO-0002", "DEMO-0003");
    private static final Set<String> DEMO_STAFF_CODES = Set.of("DEMO-STAFF-001", "DEMO-STAFF-002");

    /** Task 2 immutable hierarchy business keys; the initializer owns only these rows. */
    private static final String DEMO_ORG_CODE = "DEMO-ORG-001";
    private static final String DEMO_BRANCH_CODE = "DEMO-BR-001";
    private static final Set<String> DEMO_DEPARTMENT_CODES = Set.of("DEMO-DEP-0001", "DEMO-DEP-0002");

    /** Task 8 contract: exactly one invoice per lifecycle status. */
    private static final Set<String> INVOICE_STATUSES = Set.of("DRAFT", "ISSUED", "PAID", "VOID");

    /** Meaningless demo triage labels (docs/plan2.md Task 8: never clinical advice). */
    private static final Set<String> DEMO_TRIAGE_LABELS = Set.of("1", "2", "3", "4", "5");

    /**
     * Total CREATE events of the full fixture: 3 patients + 2 professionals
     * + 2 appointments + 2 admissions + 3 emergency visits + 4 invoices +
     * Task 2 hierarchy (1 organization + 1 branch + 2 demo departments).
     */
    private static final long FULL_AUDIT_LEDGER_SIZE = 20;

    @Autowired
    DemoDataInitializer initializer;

    @Autowired
    PatientRepository patients;

    @Autowired
    StaffMemberRepository staff;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    AdmissionRepository admissions;

    @Autowired
    EmergencyVisitRepository emergencyVisits;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    DashboardService dashboardService;

    @Autowired
    HospitalOrganizationRepository organizations;

    @Autowired
    BranchRepository branches;

    @Autowired
    DepartmentRepository departments;

    /**
     * The flag creates exactly the named synthetic cohort at startup; this
     * test never calls the seeder itself, so a green run proves the runner
     * executed during context startup.
     */
    @Test
    void enabledFlagSeedsTheNamedSyntheticCohortAtStartup() {
        List<Patient> demoPatients = demoPatients();
        assertEquals(3, demoPatients.size(), "the demo cohort must contain exactly three synthetic patients");
        assertEquals(DEMO_MRNS, demoPatients.stream()
                .map(Patient::getMedicalRecordNumber)
                .collect(Collectors.toSet()), "demo patients must carry the stable DEMO medical record numbers");
        assertTrue(demoPatients.stream().allMatch(p -> p.getFullName().startsWith("Demo Patient ")),
                "demo patient names must be obviously synthetic");
        assertTrue(demoPatients.stream().allMatch(Patient::isActive), "demo patients must be active records");

        List<StaffMember> demoStaff = demoStaff();
        assertEquals(2, demoStaff.size(), "the demo cohort must contain exactly two synthetic professionals");
        assertEquals(DEMO_STAFF_CODES, demoStaff.stream()
                .map(StaffMember::getEmployeeCode)
                .collect(Collectors.toSet()), "demo professionals must carry the stable DEMO employee codes");
        assertTrue(demoStaff.stream().allMatch(s -> s.getFullName().startsWith("Demo ")),
                "professional names must be obviously synthetic");

        List<Appointment> demoAppointments = appointments.findAll().stream().toList();
        assertEquals(2, demoAppointments.size(), "the demo cohort must contain exactly two synthetic appointments");
    }

    // ------------------------------------------------------------------
    // Task 2: the hierarchy foundation is part of the demo cohort.
    // ------------------------------------------------------------------

    /**
     * Task 2: the initializer owns exactly one synthetic organization and
     * one default branch under immutable business keys, and the branch is
     * active and bound to that organization.
     */
    @Test
    void demoHierarchyIsOneStableSyntheticOrganizationWithOneDefaultBranch() {
        List<HospitalOrganization> orgs = organizations.findAll().stream().toList();
        assertEquals(1, orgs.size(), "the initializer must own exactly one organization");
        assertEquals(DEMO_ORG_CODE, orgs.get(0).getCode(), "the organization code must be the immutable demo key");
        assertTrue(orgs.get(0).getName().startsWith("Demo "), "the organization name must be obviously synthetic");

        List<Branch> demoBranches = branches.findAll().stream().toList();
        assertEquals(1, demoBranches.size(), "the initializer must own exactly one default branch");
        Branch branch = demoBranches.get(0);
        assertEquals(DEMO_BRANCH_CODE, branch.getCode(), "the branch code must be the immutable demo key");
        assertTrue(branch.isActive(), "the default demo branch must be active");
        assertTrue(branch.getName().startsWith("Demo "), "the branch name must be obviously synthetic");
        assertFalse(branch.getLocationLabel().isBlank(), "the branch must carry a location label");
        assertEquals(orgs.get(0).getId(), branch.getOrganization().getId(),
                "the default branch must belong to the demo organization");
    }

    /**
     * Task 2: only initializer-owned demo departments are branch-assigned —
     * every assigned row carries a DEMO-DEP stable key and resolves to the
     * demo branch. Unknown pre-existing rows (never seeded here) are
     * covered by the dedicated preservation test.
     */
    @Test
    void demoDepartmentsAreAssignedOnlyToTheDemoOwnedBranch() {
        Branch branch = branches.findAll().stream().findFirst().orElseThrow();
        List<Department> assigned = departments.findAll().stream()
                .filter(department -> department.getBranch() != null)
                .toList();
        assertEquals(2, assigned.size(), "the initializer must own exactly two demo departments");
        assertEquals(DEMO_DEPARTMENT_CODES, assigned.stream()
                        .map(Department::getCode).collect(Collectors.toSet()),
                "assigned departments must carry exactly the stable DEMO-DEP keys");
        assertTrue(assigned.stream().allMatch(department -> department.getName().startsWith("Demo ")),
                "demo department names must be obviously synthetic");
        assertTrue(assigned.stream().allMatch(department -> branch.getId().equals(department.getBranch().getId())),
                "every assigned department must resolve to the demo default branch");
    }

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
        assertEquals(2, departments.findAll().stream()
                        .filter(department -> department.getBranch() != null).count(),
                "reruns must not assign or create additional owned departments");
        assertEquals(1, organizations.count(), "reruns must not inflate organizations");
        assertEquals(1, branches.count(), "reruns must not inflate branches");
    }

    /** Referential integrity: every appointment reference resolves to a seeded record. */
    @Test
    void everySeededAppointmentReferenceResolvesToASeededRecord() {
        List<Appointment> demoAppointments = appointments.findAll().stream().toList();
        assertFalse(demoAppointments.isEmpty(), "the enabled flag must have seeded appointments");
        for (Appointment appointment : demoAppointments) {
            UUID patientId = UUID.fromString(appointment.getPatientId());
            Patient patient = patients.findById(patientId)
                    .orElseThrow(() -> new AssertionError(
                            "appointment patientId must resolve to a persisted patient: " + patientId));
            assertTrue(patient.getMedicalRecordNumber().startsWith("DEMO-"),
                    "the resolved patient must belong to the synthetic demo cohort");
            assertEquals(patientId.toString(), appointment.getPatientId(),
                    "the stored reference must be the canonical UUID string of the persisted patient");

            UUID professionalId = UUID.fromString(appointment.getProfessionalId());
            StaffMember professional = staff.findById(professionalId)
                    .orElseThrow(() -> new AssertionError(
                            "appointment professionalId must resolve to a persisted professional: " + professionalId));
            assertTrue(professional.getEmployeeCode().startsWith("DEMO-STAFF-"),
                    "the resolved professional must belong to the synthetic demo cohort");
            assertEquals(professionalId.toString(), appointment.getProfessionalId(),
                    "the stored reference must be the canonical UUID string of the persisted professional");

            assertTrue(List.of("scheduled", "confirmed", "completed", "cancelled")
                            .contains(appointment.getStatus()),
                    "seeded appointment status must follow the lowercase contract");
            assertDoesNotThrow(() -> LocalDateTime.parse(appointment.getScheduledAt()),
                    "seeded scheduledAt must stay a parseable typed ISO value");
        }
    }

    /**
     * Task 8 composition: the seeded care-operation fixtures follow the
     * approved exact status distribution — one open ADMITTED admission, one
     * DISCHARGED admission for the dashboard-exclusion proof, one WAITING and
     * one IN_TREATMENT emergency visit (proving the active sum) plus one
     * CLOSED visit, and exactly one invoice in each lifecycle status. No
     * volume beyond the named rows.
     */
    @Test
    void careOperationFixturesFollowTheApprovedComposition() {
        List<Admission> seededAdmissions = admissions.findAll().stream().toList();
        assertEquals(2, seededAdmissions.size(), "the demo cohort must contain exactly two synthetic admissions");
        Map<String, List<Admission>> admissionsByStatus = seededAdmissions.stream()
                .collect(Collectors.groupingBy(Admission::getStatus));
        assertEquals(Set.of("ADMITTED", "DISCHARGED"), admissionsByStatus.keySet(),
                "admission fixtures must cover exactly the open and discharged lifecycle states");
        assertEquals(1, admissionsByStatus.get("ADMITTED").size(), "exactly one admission must be ADMITTED");
        assertEquals(1, admissionsByStatus.get("DISCHARGED").size(), "exactly one admission must be DISCHARGED");
        Admission openAdmission = admissionsByStatus.get("ADMITTED").get(0);
        assertNull(openAdmission.getDischargedAt(), "the ADMITTED admission must be open (dischargedAt null)");
        assertNotNull(admissionsByStatus.get("DISCHARGED").get(0).getDischargedAt(),
                "the DISCHARGED admission must carry a discharge timestamp");
        assertTrue(seededAdmissions.stream().allMatch(a -> a.getReason().startsWith("Demo ")),
                "admission reasons must be obviously synthetic demo workflow labels");

        List<EmergencyVisit> seededVisits = emergencyVisits.findAll().stream().toList();
        assertEquals(3, seededVisits.size(), "the demo cohort must contain exactly three synthetic emergency visits");
        Map<String, List<EmergencyVisit>> visitsByStatus = seededVisits.stream()
                .collect(Collectors.groupingBy(EmergencyVisit::getStatus));
        assertEquals(Set.of("WAITING", "IN_TREATMENT", "CLOSED"), visitsByStatus.keySet(),
                "emergency fixtures must cover both active states and the closed terminal state");
        assertEquals(1, visitsByStatus.get("WAITING").size(), "exactly one visit must be WAITING");
        assertEquals(1, visitsByStatus.get("IN_TREATMENT").size(), "exactly one visit must be IN_TREATMENT");
        assertEquals(1, visitsByStatus.get("CLOSED").size(), "exactly one visit must be CLOSED");
        assertTrue(seededVisits.stream().allMatch(v -> DEMO_TRIAGE_LABELS.contains(v.getTriageLevel())),
                "triage must stay the meaningless 1-5 demo label");
        assertTrue(seededVisits.stream().allMatch(v -> v.getChiefComplaint().startsWith("Demo ")),
                "emergency complaints must be obviously synthetic demo workflow labels");

        List<Invoice> seededInvoices = invoices.findAll().stream().toList();
        assertEquals(4, seededInvoices.size(), "the demo cohort must contain exactly four synthetic invoices");
        Map<String, List<Invoice>> invoicesByStatus = seededInvoices.stream()
                .collect(Collectors.groupingBy(Invoice::getStatus));
        assertEquals(INVOICE_STATUSES, invoicesByStatus.keySet(),
                "invoice fixtures must cover exactly the DRAFT, ISSUED, PAID, and VOID states");
        for (String status : INVOICE_STATUSES) {
            assertEquals(1, invoicesByStatus.get(status).size(),
                    "exactly one invoice must exist in status " + status);
        }
        assertTrue(seededInvoices.stream().allMatch(i -> i.getInvoiceNumber().startsWith("DEMO-INV-")),
                "invoice numbers must carry the obvious DEMO-INV- prefix");
        assertEquals(4, seededInvoices.stream().map(Invoice::getInvoiceNumber).distinct().count(),
                "invoice numbers must stay unique stable keys");
        assertTrue(seededInvoices.stream().allMatch(i -> i.getAmount().matches("\\d+\\.\\d{2}")),
                "invoice amounts must stay display-only simulation strings");
        assertTrue(seededInvoices.stream().allMatch(i -> i.getCurrency().equals("USD")),
                "invoice currencies must stay display-only demo labels");
    }

    /**
     * Task 8 referential integrity: every care-operation row references an
     * existing seeded DEMO patient by canonical UUID string and carries
     * parseable typed ISO timestamps.
     */
    @Test
    void careOperationReferencesResolveToExistingDemoPatients() {
        List<Long> countedCareOperationRows = List.of(
                admissions.count(), emergencyVisits.count(), invoices.count());
        assertEquals(List.of(2L, 3L, 4L), countedCareOperationRows,
                "the enabled flag must have seeded the care-operation fixtures");

        for (Admission admission : admissions.findAll()) {
            Patient patient = resolveDemoPatient(admission.getPatientId(), "admission");
            assertEquals(patient.getId().toString(), admission.getPatientId(),
                    "the stored admission reference must be the canonical UUID string of the persisted patient");
            assertDoesNotThrow(() -> LocalDateTime.parse(admission.getAdmittedAt()),
                    "seeded admittedAt must stay a parseable typed ISO value");
            if (admission.getDischargedAt() != null) {
                assertDoesNotThrow(() -> LocalDateTime.parse(admission.getDischargedAt()),
                        "seeded dischargedAt must stay a parseable typed ISO value");
            }
        }
        for (EmergencyVisit visit : emergencyVisits.findAll()) {
            Patient patient = resolveDemoPatient(visit.getPatientId(), "emergency visit");
            assertEquals(patient.getId().toString(), visit.getPatientId(),
                    "the stored emergency reference must be the canonical UUID string of the persisted patient");
            assertDoesNotThrow(() -> LocalDateTime.parse(visit.getArrivalAt()),
                    "seeded arrivalAt must stay a parseable typed ISO value");
        }
        for (Invoice invoice : invoices.findAll()) {
            Patient patient = resolveDemoPatient(invoice.getPatientId(), "invoice");
            assertEquals(patient.getId().toString(), invoice.getPatientId(),
                    "the stored invoice reference must be the canonical UUID string of the persisted patient");
        }
    }

    /**
     * Audit evidence: the startup seeding records exactly one CREATE event
     * per newly created record (3 patients + 2 professionals + 2
     * appointments + 2 admissions + 3 emergency visits + 4 invoices + the
     * Task 2 hierarchy of 1 organization + 1 branch + 2 demo departments)
     * under the same resource-type conventions the services use. No user is
     * authenticated during startup, so {@link AuditService} attributes
     * every event to the system actor. Reused hierarchy rows record
     * nothing, which is what keeps reruns audit-idempotent.
     */
    @Test
    void seededCreatesProduceSystemActorAuditEvents() {
        List<AuditEvent> events = auditEvents.findAll().stream().toList();
        assertEquals(FULL_AUDIT_LEDGER_SIZE, events.size(),
                "seeding must record exactly one CREATE audit event per newly created record "
                        + "(3 patients + 2 professionals + 2 appointments + 2 admissions + 3 emergency visits "
                        + "+ 4 invoices + 1 organization + 1 branch + 2 departments)");
        for (AuditEvent event : events) {
            assertEquals("CREATE", event.getAction(), "seeded creations must be recorded as CREATE events");
            assertEquals("system", event.getActor(),
                    "startup seeding has no authenticated user, so the actor must be system");
        }
        Map<String, List<AuditEvent>> byType = events.stream()
                .collect(Collectors.groupingBy(AuditEvent::getResourceType));
        assertEquals(Set.of("Patient", "StaffMember", "Appointment", "Admission", "EmergencyVisit", "Invoice",
                        "HospitalOrganization", "Branch", "Department"),
                byType.keySet(), "event resource types must follow the conventions the services use");
        assertEquals(3, byType.get("Patient").size(), "each newly created seeded patient must produce one CREATE event");
        assertEquals(2, byType.get("StaffMember").size(), "each newly created seeded professional must produce one CREATE event");
        assertEquals(2, byType.get("Appointment").size(), "each newly created seeded appointment must produce one CREATE event");
        assertEquals(2, byType.get("Admission").size(), "each newly created seeded admission must produce one CREATE event");
        assertEquals(3, byType.get("EmergencyVisit").size(), "each newly created seeded emergency visit must produce one CREATE event");
        assertEquals(4, byType.get("Invoice").size(), "each newly created seeded invoice must produce one CREATE event");
        assertEquals(1, byType.get("HospitalOrganization").size(), "the organization insert must produce one CREATE event");
        assertEquals(1, byType.get("Branch").size(), "the default-branch insert must produce one CREATE event");
        assertEquals(2, byType.get("Department").size(), "each demo department insert must produce one CREATE event");
        assertEquals(DEMO_MRNS, byType.get("Patient").stream().map(AuditEvent::getDetails).collect(Collectors.toSet()),
                "patient events must carry the MRN as details, matching the PatientService convention");
        assertTrue(byType.get("StaffMember").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("Appointment").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("Admission").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("EmergencyVisit").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("Invoice").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("HospitalOrganization").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("Branch").stream().allMatch(e -> "created".equals(e.getDetails()))
                        && byType.get("Department").stream().allMatch(e -> "created".equals(e.getDetails())),
                "non-patient events must carry the non-sensitive 'created' detail");
        assertEquals(demoPatients().stream().map(p -> p.getId().toString()).collect(Collectors.toSet()),
                byType.get("Patient").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each patient event must reference the canonical id of the persisted record");
        assertEquals(demoStaff().stream().map(s -> s.getId().toString()).collect(Collectors.toSet()),
                byType.get("StaffMember").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each professional event must reference the canonical id of the persisted record");
        assertEquals(appointments.findAll().stream().map(a -> a.getId().toString()).collect(Collectors.toSet()),
                byType.get("Appointment").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each appointment event must reference the canonical id of the persisted record");
        assertEquals(admissions.findAll().stream().map(a -> a.getId().toString()).collect(Collectors.toSet()),
                byType.get("Admission").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each admission event must reference the canonical id of the persisted record");
        assertEquals(emergencyVisits.findAll().stream().map(v -> v.getId().toString()).collect(Collectors.toSet()),
                byType.get("EmergencyVisit").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each emergency visit event must reference the canonical id of the persisted record");
        assertEquals(invoices.findAll().stream().map(i -> i.getId().toString()).collect(Collectors.toSet()),
                byType.get("Invoice").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each invoice event must reference the canonical id of the persisted record");
        assertEquals(organizations.findAll().stream().map(o -> o.getId().toString()).collect(Collectors.toSet()),
                byType.get("HospitalOrganization").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "the organization event must reference the canonical id of the persisted record");
        assertEquals(branches.findAll().stream().map(b -> b.getId().toString()).collect(Collectors.toSet()),
                byType.get("Branch").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "the branch event must reference the canonical id of the persisted record");
        assertEquals(departments.findAll().stream()
                        .filter(d -> d.getBranch() != null)
                        .map(d -> d.getId().toString()).collect(Collectors.toSet()),
                byType.get("Department").stream().map(AuditEvent::getResourceId).collect(Collectors.toSet()),
                "each department event must reference the canonical id of the persisted assigned record");
    }

    /**
     * Task 8 dashboard evidence: the real {@link DashboardService} aggregates
     * must be non-zero and exact for the fixture composition — totals, the
     * open-admission bucket excluding the DISCHARGED row, the active-emergency
     * sum over WAITING + IN_TREATMENT, and the one-per-status invoice buckets.
     */
    @Test
    void dashboardAggregatesReflectExactFixtureComposition() {
        Map<String, Long> summary = dashboardService.summary();
        assertEquals(Set.of("patients", "appointments", "admissions", "emergencyVisits", "invoices",
                        "openAdmissions", "activeEmergencyVisits",
                        "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid"),
                summary.keySet(), "the dashboard must expose exactly the contract keys");
        assertEquals(3L, summary.get("patients"), "patients total must count the three demo patients");
        assertEquals(2L, summary.get("appointments"), "appointments total must count the two demo appointments");
        assertEquals(2L, summary.get("admissions"), "admissions total must count both admission fixtures");
        assertEquals(3L, summary.get("emergencyVisits"), "emergencyVisits total must count all three visit fixtures");
        assertEquals(4L, summary.get("invoices"), "invoices total must count all four invoice fixtures");
        assertEquals(1L, summary.get("openAdmissions"),
                "openAdmissions must count only the ADMITTED fixture and exclude the DISCHARGED one");
        assertEquals(2L, summary.get("activeEmergencyVisits"),
                "activeEmergencyVisits must sum WAITING and IN_TREATMENT and exclude CLOSED");
        assertEquals(1L, summary.get("invoicesDraft"), "the DRAFT invoice bucket must hold exactly the one DRAFT fixture");
        assertEquals(1L, summary.get("invoicesIssued"), "the ISSUED invoice bucket must hold exactly the one ISSUED fixture");
        assertEquals(1L, summary.get("invoicesPaid"), "the PAID invoice bucket must hold exactly the one PAID fixture");
        assertEquals(1L, summary.get("invoicesVoid"), "the VOID invoice bucket must hold exactly the one VOID fixture");
    }

    /**
     * Idempotency: a second run changes no counts, creates no duplicates,
     * adds no audit events, and leaves the hierarchy business keys stable.
     */
    @Test
    void repeatedInitializationIsIdempotentWithoutDuplicateInflation() {
        long patientsBefore = patients.count();
        long staffBefore = staff.count();
        long appointmentsBefore = appointments.count();
        long admissionsBefore = admissions.count();
        long emergencyVisitsBefore = emergencyVisits.count();
        long invoicesBefore = invoices.count();
        long organizationsBefore = organizations.count();
        long branchesBefore = branches.count();
        long departmentsBefore = departments.count();
        assertEquals(3, demoPatients().size());
        assertEquals(2, demoStaff().size());
        assertEquals(2, appointmentsBefore);
        assertEquals(2, admissionsBefore);
        assertEquals(3, emergencyVisitsBefore);
        assertEquals(4, invoicesBefore);
        assertEquals(1, organizationsBefore);
        assertEquals(1, branchesBefore);
        assertEquals(2, departments.findAll().stream()
                        .filter(d -> d.getBranch() != null).count(),
                "exactly the two demo departments are assigned regardless of preserved legacy rows");

        long auditEventsBefore = auditEvents.count();
        assertEquals(FULL_AUDIT_LEDGER_SIZE, auditEventsBefore,
                "the startup run must have recorded exactly the twenty CREATE events");
        initializer.seedDemoCohort();
        initializer.seedDemoCohort();

        assertEquals(auditEventsBefore, auditEvents.count(),
                "a repeated seed must reuse existing records and add zero new audit events");
        assertEquals(patientsBefore, patients.count(),
                "a repeated seed must not create additional patients");
        assertEquals(staffBefore, staff.count(),
                "a repeated seed must not create additional professionals");
        assertEquals(appointmentsBefore, appointments.count(),
                "a repeated seed must not create additional appointments");
        assertEquals(admissionsBefore, admissions.count(),
                "a repeated seed must not create additional admissions");
        assertEquals(emergencyVisitsBefore, emergencyVisits.count(),
                "a repeated seed must not create additional emergency visits");
        assertEquals(invoicesBefore, invoices.count(),
                "a repeated seed must not create additional invoices");
        assertEquals(organizationsBefore, organizations.count(),
                "a repeated seed must not create additional organizations");
        assertEquals(branchesBefore, branches.count(),
                "a repeated seed must not create additional branches");
        assertEquals(departmentsBefore, departments.count(),
                "a repeated seed must not create additional departments");
        assertEquals(DEMO_ORG_CODE, organizations.findAll().stream().findFirst().orElseThrow().getCode(),
                "the organization business key must stay unchanged after a repeated seed");
        assertEquals(DEMO_BRANCH_CODE, branches.findAll().stream().findFirst().orElseThrow().getCode(),
                "the branch business key must stay unchanged after a repeated seed");
        assertEquals(DEMO_DEPARTMENT_CODES, departments.findAll().stream()
                        .filter(d -> d.getBranch() != null)
                        .map(Department::getCode).collect(Collectors.toSet()),
                "the demo department keys must stay unchanged after a repeated seed");
        assertEquals(DEMO_MRNS, demoPatients().stream()
                .map(Patient::getMedicalRecordNumber)
                .collect(Collectors.toSet()), "the stable demo keys must stay unchanged after a repeated seed");
        assertEquals(DEMO_STAFF_CODES, demoStaff().stream()
                .map(StaffMember::getEmployeeCode)
                .collect(Collectors.toSet()), "the stable demo keys must stay unchanged after a repeated seed");
        assertEquals(Set.of("DEMO-INV-0001", "DEMO-INV-0002", "DEMO-INV-0003", "DEMO-INV-0004"),
                invoices.findAll().stream().map(Invoice::getInvoiceNumber).collect(Collectors.toSet()),
                "the stable invoice numbers must stay unchanged after a repeated seed");
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
        AppointmentRepository appointmentRepository = Mockito.mock(AppointmentRepository.class);
        AdmissionRepository admissionRepository = Mockito.mock(AdmissionRepository.class);
        EmergencyVisitRepository emergencyVisitRepository = Mockito.mock(EmergencyVisitRepository.class);
        InvoiceRepository invoiceRepository = Mockito.mock(InvoiceRepository.class);
        HospitalOrganizationRepository organizationRepository = Mockito.mock(HospitalOrganizationRepository.class);
        BranchRepository branchRepository = Mockito.mock(BranchRepository.class);
        DepartmentRepository departmentRepository = Mockito.mock(DepartmentRepository.class);
        AuditService auditService = Mockito.mock(AuditService.class);
        new ApplicationContextRunner()
                .withUserConfiguration(DemoDataInitializer.class)
                .withBean("patientRepository", PatientRepository.class, () -> patientRepository)
                .withBean("staffMemberRepository", StaffMemberRepository.class, () -> staffMemberRepository)
                .withBean("appointmentRepository", AppointmentRepository.class, () -> appointmentRepository)
                .withBean("admissionRepository", AdmissionRepository.class, () -> admissionRepository)
                .withBean("emergencyVisitRepository", EmergencyVisitRepository.class, () -> emergencyVisitRepository)
                .withBean("invoiceRepository", InvoiceRepository.class, () -> invoiceRepository)
                .withBean("organizationRepository", HospitalOrganizationRepository.class, () -> organizationRepository)
                .withBean("branchRepository", BranchRepository.class, () -> branchRepository)
                .withBean("departmentRepository", DepartmentRepository.class, () -> departmentRepository)
                .withBean("auditService", AuditService.class, () -> auditService)
                .run(context -> {
                    assertTrue(context.getBeansOfType(DemoDataInitializer.class).isEmpty(),
                            "the initializer bean must not exist when the demo flag is absent (default off)");
                    Mockito.verifyNoInteractions(patientRepository, staffMemberRepository, appointmentRepository,
                            admissionRepository, emergencyVisitRepository, invoiceRepository,
                            organizationRepository, branchRepository, departmentRepository, auditService);
                });
    }

    private Patient resolveDemoPatient(String patientIdValue, String rowKind) {
        UUID patientId = UUID.fromString(patientIdValue);
        Patient patient = patients.findById(patientId)
                .orElseThrow(() -> new AssertionError(
                        rowKind + " patientId must resolve to a persisted patient: " + patientId));
        assertTrue(patient.getMedicalRecordNumber().startsWith("DEMO-"),
                "the resolved " + rowKind + " patient must belong to the synthetic demo cohort");
        return patient;
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

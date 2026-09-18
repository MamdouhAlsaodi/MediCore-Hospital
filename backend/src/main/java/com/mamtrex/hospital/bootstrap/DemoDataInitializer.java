package com.mamtrex.hospital.bootstrap;

import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionBedAssignment;
import com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
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
import com.mamtrex.hospital.staff.StaffAvailability;
import com.mamtrex.hospital.staff.StaffAvailabilityRepository;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Opt-in synthetic demo cohort seeder (docs/plan1.md Task 11, docs/plan2.md
 * Task 8, docs/plan3.md Tasks 2, 4, and 12).
 *
 * <p>The bean exists only when {@code medicore.demo.seed=true} (default off,
 * typically supplied through the {@code MEDICORE_DEMO_SEED} environment
 * variable). When enabled, startup inserts the coherent three-branch
 * synthetic operations cohort under one clearly synthetic organization
 * ({@code DEMO-ORG-001}): three unmistakably synthetic branches of varied
 * size ({@code DEMO-BR-001} default/main, {@code DEMO-BR-002} north,
 * {@code DEMO-BR-003} harbor), branch-owned departments and professionals,
 * dated half-open availability intervals, beds in every operational status
 * ({@code AVAILABLE}/{@code OCCUPIED}/{@code MAINTENANCE}/
 * {@code OUT_OF_SERVICE}), same-branch patients, appointments with the
 * Task 9 bounded duration inside availability, open admissions that
 * genuinely occupy a bed through a live {@code AdmissionBedAssignment} row,
 * discharged admissions with no bed, emergency visits, and simulated
 * invoices — every reference same-branch and valid. All values are
 * fabricated demo data on the {@code synthetic.test} demo domain; no real
 * personal, clinical, or financial data ever enters this class.</p>
 *
 * <p>Every insert is lookup-before-create against a stable business key —
 * the organization code, the (organization, code) branch pair, the
 * (branch, code) department pair, the bed (branch, ward, room, bedNumber)
 * identity, the patient medical record number, the staff employee code, the
 * appointment composite key (patient + professional + scheduledAt + type),
 * the availability composite key (branch + professional + startsAt + endsAt),
 * an admission composite key (patient + admittedAt + reason), an emergency
 * composite key (patient + arrivalAt + complaint), and the unique invoice
 * number — so repeated startups insert zero records and add zero audit
 * events. The bed-assignment action is idempotent through the live
 * {@code (admission)} assignment row, so a run interrupted between the
 * admission insert and the bed assignment self-heals on the next startup
 * instead of leaving a contradiction. The seeder contains no delete,
 * update, or reset operation: existing rows are never touched, and unknown
 * pre-existing unassigned rows are never mass-updated. It creates no user
 * accounts and no credentials; passwords and secrets never appear here or
 * in the demo data (the admin and review accounts remain owned by
 * {@code DevAdminInitializer} and the runtime environment).</p>
 *
 * <p>Care-operation rows follow the Task 7A/8 persistence representation:
 * app-owned UUID branch references and String-typed canonical UUID patient
 * references. Their fixture keys use fresh 2031-era timestamps and the
 * {@code DEMO-INV-01xx/02xx/03xx} invoice numbers so a store that still
 * holds the pre-Task-12 Task 8 cohort can never shadow the branch-owned
 * rows: those older rows keep their null ownership, stay invisible through
 * every branch-scoped read exactly like any legacy row, and are never
 * adopted, renumbered, or reassigned (the entities expose no ownership
 * mutation, and reassigning unknown or historical rows stays forbidden).</p>
 *
 * <p>Rows are written directly through the repositories; every <em>newly
 * created</em> row is recorded through {@link AuditService} using the same
 * action/resource-type/detail conventions the owning services use
 * ({@code CREATE} for inserts; the admission bed-assignment action as
 * {@code UPDATE Admission} with the {@code bed: <id>} detail, matching
 * {@code AdmissionService}). The events are context-aware system events:
 * because no acting assignment exists during startup, the seeder records
 * each event under a system principal whose {@link ActingContext} carries
 * exactly the organization and owning branch of the resource it describes —
 * never a fabricated assignment, role, scope, or department (those columns
 * stay null). The organization's own event keeps the fully context-less shape, and the
 * security context is cleared immediately after every recording. Startup
 * events carry no correlation id (no request boundary exists). Reused
 * records record nothing — the absence of events for existing rows is
 * exactly what keeps repeated startups idempotent in the audit trail too.</p>
 */
@Component
@ConditionalOnProperty(name = "medicore.demo.seed", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private final PatientRepository patientRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final StaffAvailabilityRepository staffAvailabilityRepository;
    private final AppointmentRepository appointmentRepository;
    private final AdmissionRepository admissionRepository;
    private final AdmissionBedAssignmentRepository admissionBedAssignmentRepository;
    private final EmergencyVisitRepository emergencyVisitRepository;
    private final InvoiceRepository invoiceRepository;
    private final BedRepository bedRepository;
    private final HospitalOrganizationRepository organizationRepository;
    private final HospitalFacilityRepository hospitalRepository;
    private final BranchRepository branchRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    /**
     * Why fourteen constructor parameters: this bounded seeder needs the
     * whole write surface of the three-branch demo cohort — one repository
     * per seeded aggregate (including the Task 2 hierarchy, the Phase 5
     * legacy hospital, Task 6 beds, Task 9 availability, and the Task 7
     * live bed assignments) plus {@link AuditService} — and Spring
     * constructor injection keeps every collaborator explicit and fakeable
     * in tests. This remains a deliberate, documented clean-code exception
     * to the few-dependencies heuristic, not a pattern to copy elsewhere.
     * Revisit trigger: split collaborators only when a second seed profile
     * or a second persistence adapter creates a real independent actor.
     */
    public DemoDataInitializer(PatientRepository patientRepository,
                               StaffMemberRepository staffMemberRepository,
                               StaffAvailabilityRepository staffAvailabilityRepository,
                               AppointmentRepository appointmentRepository,
                               AdmissionRepository admissionRepository,
                               AdmissionBedAssignmentRepository admissionBedAssignmentRepository,
                               EmergencyVisitRepository emergencyVisitRepository,
                               InvoiceRepository invoiceRepository,
                               BedRepository bedRepository,
                               HospitalOrganizationRepository organizationRepository,
                               HospitalFacilityRepository hospitalRepository,
                               BranchRepository branchRepository,
                               DepartmentRepository departmentRepository,
                               AuditService auditService) {
        this.patientRepository = patientRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.staffAvailabilityRepository = staffAvailabilityRepository;
        this.appointmentRepository = appointmentRepository;
        this.admissionRepository = admissionRepository;
        this.admissionBedAssignmentRepository = admissionBedAssignmentRepository;
        this.emergencyVisitRepository = emergencyVisitRepository;
        this.invoiceRepository = invoiceRepository;
        this.bedRepository = bedRepository;
        this.organizationRepository = organizationRepository;
        this.hospitalRepository = hospitalRepository;
        this.branchRepository = branchRepository;
        this.departmentRepository = departmentRepository;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedDemoCohort();
    }

    /** Immutable Task 2/12 + Phase 5 hierarchy business keys; the initializer owns only these rows. */
    static final String DEMO_ORGANIZATION_CODE = "DEMO-ORG-001";
    static final String DEMO_HOSPITAL_CODE = "LEGACY-HOSPITAL-001";
    static final String DEMO_HOSPITAL_RIVERSIDE_CODE = "DEMO-HOSP-002";
    static final String DEMO_HOSPITAL_HARBORVIEW_CODE = "DEMO-HOSP-003";
    static final String DEMO_BRANCH_CODE = "DEMO-BR-001";
    static final String DEMO_BRANCH_NORTH_CODE = "DEMO-BR-002";
    static final String DEMO_BRANCH_HARBOR_CODE = "DEMO-BR-003";
    static final String DEMO_BRANCH_RIVERSIDE_EAST_CODE = "DEMO-BR-1101";
    static final String DEMO_BRANCH_RIVERSIDE_WEST_CODE = "DEMO-BR-1102";
    static final String DEMO_BRANCH_HARBORVIEW_PIER_CODE = "DEMO-BR-2201";
    static final String DEMO_BRANCH_HARBORVIEW_DUNE_CODE = "DEMO-BR-2202";

    /**
     * Seeds the synthetic three-branch demo cohort; safe to call repeatedly
     * (idempotent: a second run inserts zero records and zero events). The
     * Task 2 hierarchy is seeded first so every later fixture keeps a
     * stable foundation; the branch cohorts are then seeded per branch.
     */
    void seedDemoCohort() {
        Branch main = seedDemoHierarchy();
        HospitalFacility hospital = main.getHospital();
        Branch north = seedBranch(hospital, DEMO_BRANCH_NORTH_CODE,
                "Demo North Branch", "9 Demo North Road",
                "DEMO-DEP-0101", "Demo General Practice", "general medicine", "Demo Wing C");
        Branch harbor = seedBranch(hospital, DEMO_BRANCH_HARBOR_CODE,
                "Demo Harbor Branch", "17 Demo Harbor Lane",
                "DEMO-DEP-0201", "Demo Harbor Clinic", "general medicine", "Demo Pavilion D");

        seedDefaultBranchCohort(main);
        seedNorthBranchCohort(north);
        seedHarborBranchCohort(harbor);
        // Phase 5 US1 (T038): two deterministic fixture hospitals with two
        // bare branches each — the network spans three hospitals and three
        // IANA zones while the Phase 4 workflow cohort stays untouched.
        seedFixtureHospital(organizationRepository.findByCode(DEMO_ORGANIZATION_CODE).orElseThrow(),
                DEMO_HOSPITAL_RIVERSIDE_CODE, "Demo Riverside Hospital", "Demo Riverside Region",
                "America/New_York",
                DEMO_BRANCH_RIVERSIDE_EAST_CODE, "Demo Riverside East Branch", "100 Demo Riverside Road",
                DEMO_BRANCH_RIVERSIDE_WEST_CODE, "Demo Riverside West Branch", "102 Demo Riverside Road");
        seedFixtureHospital(organizationRepository.findByCode(DEMO_ORGANIZATION_CODE).orElseThrow(),
                DEMO_HOSPITAL_HARBORVIEW_CODE, "Demo Harborview Hospital", "Demo Harborview Region",
                "Asia/Tokyo",
                DEMO_BRANCH_HARBORVIEW_PIER_CODE, "Demo Harborview Pier Branch", "200 Demo Pier Parade",
                DEMO_BRANCH_HARBORVIEW_DUNE_CODE, "Demo Harborview Dune Branch", "202 Demo Dune Drive");
    }

    /**
     * One Phase 5 US1 fixture hospital with two bare synthetic branches,
     * all under stable business keys and lookup-before-create: a rerun
     * inserts nothing, and only the creating paths record audit events (the
     * hospital event keeps the context-less shape — a hospital row has no
     * owning branch — while each branch event carries the derived acting
     * hospital). The branches carry the hospital's own validated IANA zone
     * so every fixture branch stays zone-resolvable.
     */
    private void seedFixtureHospital(HospitalOrganization organization, String hospitalCode, String hospitalName,
                                     String regionLabel, String timeZoneId,
                                     String firstBranchCode, String firstBranchName, String firstBranchLocation,
                                     String secondBranchCode, String secondBranchName, String secondBranchLocation) {
        HospitalFacility hospital = hospitalRepository
                .findByOrganizationIdAndCode(organization.getId(), hospitalCode)
                .orElseGet(() -> {
                    HospitalFacility saved = hospitalRepository.save(new HospitalFacility(
                            organization, hospitalCode, hospitalName, regionLabel, timeZoneId));
                    auditService.record("CREATE", "HospitalFacility",
                            saved.getId().toString(), "created");
                    return saved;
                });
        createBranchIfAbsent(hospital, firstBranchCode, firstBranchName, firstBranchLocation);
        createBranchIfAbsent(hospital, secondBranchCode, secondBranchName, secondBranchLocation);
    }

    /** Lookup-before-create branch insert plus its branch-attributed CREATE event. */
    private void createBranchIfAbsent(HospitalFacility hospital, String code, String name, String location) {
        if (branchRepository.findByHospitalIdAndCode(hospital.getId(), code).isPresent()) {
            return;
        }
        Branch saved = branchRepository.save(
                new Branch(hospital, code, name, location, hospital.getTimeZone()));
        recordSystemEvent(saved, "CREATE", "Branch", saved.getId().toString(), "created");
    }

    /**
     * Task 2 + Phase 5 hierarchy fixtures: the synthetic organization, the
     * one deterministic legacy hospital (Phase 5 T027 — the Phase 4 cohort
     * is preserved under it unchanged), and only the departments this
     * initializer owns on the default branch, each created assigned to that
     * branch. Stable keys: the organization code, the (organization, code)
     * hospital pair, the (hospital, code) branch pair, and the (branch,
     * code) department pair. An existing row is reused untouched; only the
     * creating path records an audit event, so reruns add nothing.
     */
    private Branch seedDemoHierarchy() {
        HospitalOrganization organization = organizationRepository
                .findByCode(DEMO_ORGANIZATION_CODE)
                .orElseGet(() -> {
                    HospitalOrganization saved = organizationRepository.save(
                            new HospitalOrganization(DEMO_ORGANIZATION_CODE, "Demo Synthetic Hospital"));
                    // The organization row has no owning branch: its event
                    // keeps the fully context-less startup shape.
                    auditService.record("CREATE", "HospitalOrganization",
                            saved.getId().toString(), "created");
                    return saved;
                });
        HospitalFacility hospital = hospitalRepository
                .findByOrganizationIdAndCode(organization.getId(), DEMO_HOSPITAL_CODE)
                .orElseGet(() -> {
                    HospitalFacility saved = hospitalRepository.save(new HospitalFacility(
                            organization, DEMO_HOSPITAL_CODE, "Demo Legacy Hospital",
                            "Demo Region", "UTC"));
                    // The hospital row has no owning branch: its event also
                    // keeps the fully context-less startup shape.
                    auditService.record("CREATE", "HospitalFacility",
                            saved.getId().toString(), "created");
                    return saved;
                });
        Branch main = branchRepository
                .findByHospitalIdAndCode(hospital.getId(), DEMO_BRANCH_CODE)
                .orElseGet(() ->
                        createBranch(hospital, DEMO_BRANCH_CODE, "Demo Main Branch", "1 Demo Campus",
                                java.time.ZoneId.of("UTC")));
        seedDemoDepartment(main, "DEMO-DEP-0001", "Demo Internal Medicine", "internal medicine", "Demo Tower A");
        seedDemoDepartment(main, "DEMO-DEP-0002", "Demo Emergency Care", "emergency medicine", "Demo Tower B");
        return main;
    }

    /**
     * One Task 12 synthetic branch of the demo organization's legacy
     * hospital with its one initializer-owned department, both under stable
     * business keys, so the three branches carry obviously different size
     * and geography.
     */
    private Branch seedBranch(HospitalFacility hospital, String branchCode, String branchName,
                              String location, String departmentCode, String departmentName,
                              String specialty, String departmentLocation) {
        // Phase 4 (FR-012): each demo branch carries its own documented,
        // stable IANA zone (main=UTC, north=America/New_York,
        // harbor=Asia/Tokyo) so branch-local time semantics are provably
        // exercised. The zone comes from this fixture table — never from
        // host time. Legacy rows keep a null zone and are never guessed.
        java.time.ZoneId zone = switch (branchCode) {
            case DEMO_BRANCH_CODE -> java.time.ZoneId.of("UTC");
            case DEMO_BRANCH_NORTH_CODE -> java.time.ZoneId.of("America/New_York");
            case DEMO_BRANCH_HARBOR_CODE -> java.time.ZoneId.of("Asia/Tokyo");
            default -> null;
        };
        Branch branch = branchRepository
                .findByHospitalIdAndCode(hospital.getId(), branchCode)
                .orElseGet(() -> createBranch(hospital, branchCode, branchName, location, zone));
        seedDemoDepartment(branch, departmentCode, departmentName, specialty, departmentLocation);
        return branch;
    }

    /** Branch insert plus its CREATE event attributed to the branch itself. */
    private Branch createBranch(HospitalFacility hospital, String code, String name,
                                String location, java.time.ZoneId zone) {
        Branch saved = branchRepository.save(new Branch(hospital, code, name, location, zone));
        recordSystemEvent(saved, "CREATE", "Branch", saved.getId().toString(), "created");
        return saved;
    }

    /**
     * One demo-owned department fixture under the stable (branch, code)
     * pair. Unknown pre-existing null-branch departments are never looked
     * at here: only rows this initializer identifies by its own key are
     * created or reused, never mass-updated.
     */
    private void seedDemoDepartment(Branch branch, String code, String name,
                                    String specialty, String location) {
        departmentRepository.findByBranchIdAndCode(branch.getId(), code)
                .orElseGet(() -> {
                    Department saved = departmentRepository.save(
                            new Department(branch, code, name, specialty, location));
                    recordSystemEvent(branch, "CREATE", "Department",
                            saved.getId().toString(), "created");
                    return saved;
                });
    }

    /**
     * The default-branch cohort (docs/plan3.md Tasks 4, 8, and 12): three
     * patients, two professionals with dated availability, two appointments,
     * one open bed-occupying admission, one discharged admission, the full
     * emergency status spread, and one invoice per lifecycle state.
     */
    private void seedDefaultBranchCohort(Branch branch) {
        Patient alpha = seedPatient(branch, "DEMO-0001", "Demo Patient Alpha", LocalDate.of(2001, 1, 1),
                "unspecified", "+10000000001", "demo.alpha@synthetic.test", "NID-DEMO-1", "1 Demo Way");
        Patient bravo = seedPatient(branch, "DEMO-0002", "Demo Patient Bravo", LocalDate.of(2002, 2, 2),
                "unspecified", "+10000000002", "demo.bravo@synthetic.test", "NID-DEMO-2", "2 Demo Way");
        Patient charlie = seedPatient(branch, "DEMO-0003", "Demo Patient Charlie", LocalDate.of(2003, 3, 3),
                "unspecified", "+10000000003", "demo.charlie@synthetic.test", "NID-DEMO-3", "3 Demo Way");

        StaffMember physician = seedStaffMember(branch, "DEMO-STAFF-001", "Demo Physician Alpha",
                "internal medicine", "DEMO-LIC-001", "Demo Internal Medicine");
        StaffMember nurse = seedStaffMember(branch, "DEMO-STAFF-002", "Demo Nurse Bravo",
                "nursing", "DEMO-LIC-002", "Demo Emergency Care");

        // Dated half-open availability: one adjacent pair proves the Task 9
        // adjacency rule, and every appointment window below sits inside one
        // interval of its own professional.
        seedAvailability(branch, physician,
                LocalDateTime.of(2031, 3, 2, 8, 0), LocalDateTime.of(2031, 3, 2, 12, 0));
        seedAvailability(branch, physician,
                LocalDateTime.of(2031, 3, 2, 12, 0), LocalDateTime.of(2031, 3, 2, 16, 0));
        seedAvailability(branch, nurse,
                LocalDateTime.of(2031, 3, 9, 8, 0), LocalDateTime.of(2031, 3, 9, 12, 0));

        seedAppointment(branch, alpha, physician,
                LocalDateTime.of(2031, 3, 2, 9, 0), 30, "consultation", "scheduled");
        seedAppointment(branch, bravo, nurse,
                LocalDateTime.of(2031, 3, 9, 10, 30), 30, "follow-up", "confirmed");

        // Beds: all four operational statuses on the default branch.
        Bed available = seedBed(branch, "Demo Ward A", "101", "01");
        Bed occupied = seedBed(branch, "Demo Ward A", "101", "02");
        seedBed(branch, "Demo Ward A", "101", "03", Bed.STATUS_MAINTENANCE);
        seedBed(branch, "Demo Ward A", "101", "04", Bed.STATUS_OUT_OF_SERVICE);

        // Admissions: the open one genuinely occupies a bed through a live
        // assignment row; the discharged one holds no bed.
        Admission openAdmission = seedAdmission(branch, alpha,
                new AdmissionFixture("2031-01-06T08:30", null, "Demo admission intake"));
        seedAdmission(branch, bravo,
                new AdmissionFixture("2031-01-04T14:00", "2031-01-09T10:15", "Demo discharge workflow"));
        assignBedIfUnassigned(branch, openAdmission, occupied);

        // Emergency visits: both active states (so the dashboard sum is
        // exercised) plus the CLOSED terminal state.
        seedEmergencyVisit(branch, charlie, new EmergencyVisitFixture(
                "2031-01-08T12:45", "3", "Demo emergency intake", "WAITING"));
        seedEmergencyVisit(branch, alpha, new EmergencyVisitFixture(
                "2031-01-09T09:15", "4", "Demo treatment workflow", "IN_TREATMENT"));
        seedEmergencyVisit(branch, bravo, new EmergencyVisitFixture(
                "2031-01-07T22:10", "2", "Demo closed visit workflow", "CLOSED"));

        // Invoices: exactly one per lifecycle state; display-only simulation.
        seedInvoice(branch, alpha, new InvoiceFixture("DEMO-INV-0101", "120.00", "USD", "DRAFT"));
        seedInvoice(branch, bravo, new InvoiceFixture("DEMO-INV-0102", "80.50", "USD", "ISSUED"));
        seedInvoice(branch, charlie, new InvoiceFixture("DEMO-INV-0103", "150.00", "USD", "PAID"));
        seedInvoice(branch, alpha, new InvoiceFixture("DEMO-INV-0104", "40.00", "USD", "VOID"));
    }

    /**
     * The north-branch cohort (varied size): two patients, two
     * professionals, one appointment, one available and one bed-occupying
     * admission bed, one open admission, one WAITING emergency visit, and
     * two simulated invoices.
     */
    private void seedNorthBranchCohort(Branch branch) {
        Patient delta = seedPatient(branch, "DEMO-0004", "Demo Patient Delta", LocalDate.of(2004, 4, 4),
                "unspecified", "+10000000004", "demo.delta@synthetic.test", "NID-DEMO-4", "4 Demo Way");
        Patient echo = seedPatient(branch, "DEMO-0005", "Demo Patient Echo", LocalDate.of(2005, 5, 5),
                "unspecified", "+10000000005", "demo.echo@synthetic.test", "NID-DEMO-5", "5 Demo Way");

        StaffMember physician = seedStaffMember(branch, "DEMO-STAFF-0101", "Demo Physician Delta",
                "general medicine", "DEMO-LIC-0101", "Demo General Practice");
        seedStaffMember(branch, "DEMO-STAFF-0102", "Demo Nurse Echo",
                "nursing", "DEMO-LIC-0102", "Demo General Practice");

        seedAvailability(branch, physician,
                LocalDateTime.of(2031, 3, 3, 9, 0), LocalDateTime.of(2031, 3, 3, 13, 0));
        seedAppointment(branch, delta, physician,
                LocalDateTime.of(2031, 3, 3, 11, 0), 30, "consultation", "confirmed");

        seedBed(branch, "Demo Ward N", "201", "01");
        Bed occupied = seedBed(branch, "Demo Ward N", "201", "02");

        Admission openAdmission = seedAdmission(branch, delta,
                new AdmissionFixture("2031-02-03T10:00", null, "Demo admission intake"));
        assignBedIfUnassigned(branch, openAdmission, occupied);

        seedEmergencyVisit(branch, echo, new EmergencyVisitFixture(
                "2031-02-04T17:20", "5", "Demo emergency intake", "WAITING"));

        seedInvoice(branch, delta, new InvoiceFixture("DEMO-INV-0201", "60.00", "USD", "ISSUED"));
        seedInvoice(branch, echo, new InvoiceFixture("DEMO-INV-0202", "90.00", "USD", "PAID"));
    }

    /**
     * The harbor-branch cohort (smallest): one patient, one professional,
     * one appointment, two available beds, one discharged admission, one
     * CLOSED emergency visit, and one simulated invoice.
     */
    private void seedHarborBranchCohort(Branch branch) {
        Patient foxtrot = seedPatient(branch, "DEMO-0006", "Demo Patient Foxtrot", LocalDate.of(2006, 6, 6),
                "unspecified", "+10000000006", "demo.foxtrot@synthetic.test", "NID-DEMO-6", "6 Demo Way");

        StaffMember physician = seedStaffMember(branch, "DEMO-STAFF-0201", "Demo Physician Foxtrot",
                "general medicine", "DEMO-LIC-0201", "Demo Harbor Clinic");

        seedAvailability(branch, physician,
                LocalDateTime.of(2031, 3, 4, 13, 0), LocalDateTime.of(2031, 3, 4, 17, 0));
        seedAppointment(branch, foxtrot, physician,
                LocalDateTime.of(2031, 3, 4, 14, 0), 30, "follow-up", "scheduled");

        seedBed(branch, "Demo Ward H", "301", "01");
        seedBed(branch, "Demo Ward H", "301", "02");

        seedAdmission(branch, foxtrot,
                new AdmissionFixture("2031-02-05T09:45", "2031-02-06T08:05", "Demo discharge workflow"));

        seedEmergencyVisit(branch, foxtrot, new EmergencyVisitFixture(
                "2031-02-06T15:30", "1", "Demo closed visit workflow", "CLOSED"));

        seedInvoice(branch, foxtrot, new InvoiceFixture("DEMO-INV-0301", "45.25", "USD", "DRAFT"));
    }

    /**
     * Stable key: the medical record number. An existing demo patient is
     * reused, never duplicated; only the creating path records the CREATE
     * audit event (details = the MRN, matching {@code PatientService}).
     */
    private Patient seedPatient(Branch branch, String mrn, String fullName, LocalDate dateOfBirth, String sex,
                                String phone, String email, String nationalId, String address) {
        return patientRepository.findByMedicalRecordNumber(mrn).orElseGet(() -> {
            Patient saved = patientRepository.save(
                    new Patient(branch, mrn, fullName, dateOfBirth, sex, phone, email, nationalId, address));
            recordSystemEvent(branch, "CREATE", "Patient", saved.getId().toString(), mrn);
            return saved;
        });
    }

    /**
     * Stable key: the employee code. {@code StaffMemberRepository} exposes no
     * derived finder (the repository layer is frozen), so the key is resolved
     * in memory over {@code findAll()} — acceptable for a bounded demo cohort.
     * Only the creating path records the CREATE audit event (details =
     * {@code created}, matching the staff controller). The department label
     * names a same-branch department so every professional reference stays
     * branch-valid.
     */
    private StaffMember seedStaffMember(Branch branch, String employeeCode, String fullName, String profession,
                                        String licenseNumber, String department) {
        return staffMemberRepository.findAll().stream()
                .filter(staff -> employeeCode.equals(staff.getEmployeeCode()))
                .findFirst()
                .orElseGet(() -> {
                    StaffMember saved = staffMemberRepository.save(
                            new StaffMember(branch, employeeCode, fullName, profession, licenseNumber, department));
                    recordSystemEvent(branch, "CREATE", "StaffMember",
                            saved.getId().toString(), "created");
                    return saved;
                });
    }

    /**
     * One dated half-open availability interval under the stable
     * (branch, professional, startsAt, endsAt) key, resolved through the
     * repository's windowed read instead of a whole-table scan. The fixture
     * intervals never overlap (exactly-adjacent intervals are allowed by the
     * Task 9 half-open contract).
     */
    private void seedAvailability(Branch branch, StaffMember professional,
                                  LocalDateTime startsAt, LocalDateTime endsAt) {
        boolean alreadySeeded = staffAvailabilityRepository
                .findWindow(branch.getId(), professional.getId(), startsAt, endsAt).stream()
                .anyMatch(interval -> startsAt.equals(interval.getStartsAt())
                        && endsAt.equals(interval.getEndsAt()));
        if (!alreadySeeded) {
            StaffAvailability saved = staffAvailabilityRepository.save(
                    new StaffAvailability(branch.getId(), professional.getId(), startsAt, endsAt));
            recordSystemEvent(branch, "CREATE", "StaffAvailability",
                    saved.getId().toString(), "created");
        }
    }

    /**
     * Stable key: patient + professional + scheduledAt + type. An existing
     * matching appointment is left exactly as it is — never duplicated,
     * updated, or deleted. New rows carry the Task 9 bounded duration and
     * the server-computed half-open window end (30-minute demo windows), and
     * every window sits inside a seeded availability interval of the same
     * professional. Only the creating path records the CREATE audit event
     * (details = {@code created}, matching {@code AppointmentService}).
     */
    private void seedAppointment(Branch branch, Patient patient, StaffMember professional,
                                 LocalDateTime scheduledAt, int durationMinutes, String type, String status) {
        String patientKey = patient.getId().toString();
        String professionalKey = professional.getId().toString();
        // Phase 4 (FR-012): the fixture wall-clock value is resolved
        // through the branch's own zone into the stored unambiguous instant
        // window; the idempotency key compares instants.
        java.time.Instant start = com.mamtrex.hospital.organization.BranchTimeService.toInstant(branch, scheduledAt);
        java.time.Instant end = start.plusSeconds(durationMinutes * 60L);
        boolean alreadySeeded = appointmentRepository.findAll().stream().anyMatch(appointment ->
                patientKey.equals(appointment.getPatientId())
                        && professionalKey.equals(appointment.getProfessionalId())
                        && start.equals(appointment.getScheduledAt())
                        && type.equals(appointment.getType()));
        if (!alreadySeeded) {
            Appointment saved = appointmentRepository.save(new Appointment(branch, patientKey, professionalKey,
                    start, durationMinutes, end, type, status));
            recordSystemEvent(branch, "CREATE", "Appointment", saved.getId().toString(), "created");
        }
    }

    /**
     * Stable key: (branch, ward, room, bedNumber), resolved through the
     * repository's derived finder. The bed is inserted directly in its
     * fixture status: the default is AVAILABLE, the client-manageable
     * operational states are reached through the entity's own legal
     * transition seam, and OCCUPIED is never written here — occupancy is
     * admission-owned and applied only by {@link #assignBedIfUnassigned}.
     */
    private Bed seedBed(Branch branch, String ward, String room, String bedNumber) {
        return seedBed(branch, ward, room, bedNumber, Bed.STATUS_AVAILABLE);
    }

    private Bed seedBed(Branch branch, String ward, String room, String bedNumber, String operationalStatus) {
        return bedRepository.findByBranchIdAndWardAndRoomAndBedNumber(branch.getId(), ward, room, bedNumber)
                .orElseGet(() -> {
                    Bed saved = bedRepository.save(new Bed(branch, ward, room, bedNumber));
                    if (!Bed.STATUS_AVAILABLE.equals(operationalStatus)) {
                        saved.changeOperationalStatus(operationalStatus);
                        bedRepository.save(saved);
                    }
                    recordSystemEvent(branch, "CREATE", "Bed", saved.getId().toString(), "created");
                    return saved;
                });
    }

    /**
     * Stable key: patient + admittedAt + reason, resolved in memory like the
     * appointments ({@code AdmissionRepository} exposes no derived finder;
     * the repository layer is frozen). A new admission is created through the
     * branch-aware constructor (server-stamped ownership at insert). When the
     * fixture carries a discharge time, the entity's own lifecycle mutation
     * applies the single legal {@code ADMITTED -> DISCHARGED} transition
     * before the insert, so the row is never persisted in an intermediate
     * state. An existing matching admission is left exactly as it is; only
     * the creating path records the CREATE audit event (details =
     * {@code created}).
     */
    private Admission seedAdmission(Branch branch, Patient patient, AdmissionFixture fixture) {
        String patientKey = patient.getId().toString();
        // Phase 4 (FR-012): fixture wall-clock strings resolve through the
        // branch's zone into the stored unambiguous instants.
        java.time.Instant admittedAt = com.mamtrex.hospital.organization.BranchTimeService
                .toInstant(branch, LocalDateTime.parse(fixture.admittedAt()));
        java.time.Instant dischargedAt = fixture.dischargedAt() == null ? null
                : com.mamtrex.hospital.organization.BranchTimeService
                        .toInstant(branch, LocalDateTime.parse(fixture.dischargedAt()));
        return admissionRepository.findAll().stream()
                .filter(admission -> patientKey.equals(admission.getPatientId())
                        && admittedAt.equals(admission.getAdmittedAt())
                        && fixture.reason().equals(admission.getReason()))
                .findFirst()
                .orElseGet(() -> {
                    Admission admission = new Admission(
                            branch.getId(), patientKey, admittedAt, fixture.reason());
                    // The lifecycle mutation is applied before the insert, so
                    // the row is never persisted in an intermediate state.
                    if (dischargedAt != null) {
                        admission.dischargeAt(dischargedAt);
                    }
                    Admission saved = admissionRepository.save(admission);
                    recordSystemEvent(branch, "CREATE", "Admission", saved.getId().toString(), "created");
                    return saved;
                });
    }

    /**
     * The admission bed-assignment action (docs/plan3.md Task 7): the live
     * {@code AdmissionBedAssignment} row IS the active assignment, so the
     * stable key is the (admission) assignment row itself. When the
     * admission holds no bed, the row is inserted, the bed is moved to its
     * admission-owned OCCUPIED state, and the action is recorded with the
     * {@code AdmissionService} convention ({@code UPDATE Admission},
     * details {@code bed: <bedId>}). A rerun finds the live row and does
     * nothing, so the action never doubles and a run interrupted between the
     * admission insert and this step self-heals on the next startup.
     */
    private void assignBedIfUnassigned(Branch branch, Admission admission, Bed bed) {
        if (admissionBedAssignmentRepository.findByAdmissionId(admission.getId()).isPresent()) {
            return;
        }
        // The bed is occupied first: if a startup is interrupted after this
        // save, the next one finds the missing assignment row and completes
        // the pair, so the two writes always converge instead of leaving a
        // standing bed/admission contradiction.
        bed.markOccupiedByAdmission();
        bedRepository.save(bed);
        admissionBedAssignmentRepository.save(
                new AdmissionBedAssignment(admission.getId(), bed.getId()));
        recordSystemEvent(branch, "UPDATE", "Admission",
                admission.getId().toString(), "bed: " + bed.getId());
    }

    /**
     * Stable key: patient + arrivalAt + chiefComplaint, resolved in memory
     * like the admissions. New rows are created through the branch-aware
     * constructor. An existing matching visit is left exactly as it is; only
     * the creating path records the CREATE audit event (details =
     * {@code created}).
     */
    private void seedEmergencyVisit(Branch branch, Patient patient, EmergencyVisitFixture fixture) {
        String patientKey = patient.getId().toString();
        java.time.Instant arrivalAt = com.mamtrex.hospital.organization.BranchTimeService
                .toInstant(branch, LocalDateTime.parse(fixture.arrivalAt()));
        boolean alreadySeeded = emergencyVisitRepository.findAll().stream().anyMatch(visit ->
                patientKey.equals(visit.getPatientId())
                        && arrivalAt.equals(visit.getArrivalAt())
                        && fixture.chiefComplaint().equals(visit.getChiefComplaint()));
        if (!alreadySeeded) {
            EmergencyVisit saved = emergencyVisitRepository.save(new EmergencyVisit(branch.getId(),
                    patientKey, arrivalAt, fixture.triageLevel(),
                    fixture.chiefComplaint(), fixture.status()));
            recordSystemEvent(branch, "CREATE", "EmergencyVisit", saved.getId().toString(), "created");
        }
    }

    /**
     * Stable key: the unique {@code invoiceNumber} ({@code DEMO-INV-...}
     * prefix), resolved through the repository's derived finder backed by the
     * DB unique constraint. New rows are created through the branch-aware
     * constructor. An existing invoice with the same number is left exactly
     * as it is — never renumbered, re-priced, or moved to another state; only
     * the creating path records the CREATE audit event (details =
     * {@code created}). Amount and currency are display-only financial
     * simulation strings.
     */
    private void seedInvoice(Branch branch, Patient patient, InvoiceFixture fixture) {
        if (invoiceRepository.findByInvoiceNumber(fixture.invoiceNumber()).isPresent()) {
            return;
        }
        Invoice saved = invoiceRepository.save(new Invoice(branch.getId(), patient.getId().toString(),
                fixture.invoiceNumber(), new java.math.BigDecimal(fixture.amount()), fixture.currency(), fixture.status()));
        recordSystemEvent(branch, "CREATE", "Invoice", saved.getId().toString(), "created");
    }

    /**
     * The context-aware system recording seam (docs/plan3.md Tasks 11 and
     * 12). {@code AuditService} reads the acting context from the security
     * context holder, and no authenticated user exists during startup — so
     * this seeder installs, for the duration of one recording, a system
     * principal whose {@link ActingContext} carries exactly the owning
     * branch of the resource the event describes. Assignment, role, scope,
     * and department stay null: no acting assignment exists at startup, while
     * organization ownership is derived directly from that branch. The context is cleared immediately after the recording, the
     * actor stays {@code system}, and no correlation id is fabricated.
     */
    private void recordSystemEvent(Branch owningBranch, String action, String resourceType,
                                   String resourceId, String details) {
        ActingContext context = new ActingContext("system", null, null, null,
                owningBranch.getOrganization().getId(), owningBranch.getHospital().getId(),
                owningBranch.getId(), null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(context, null, List.of()));
        try {
            auditService.record(action, resourceType, resourceId, details);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * One admission fixture row; an open admission carries a null
     * {@code dischargedAt} and a discharged one the fixed demo discharge
     * time applied through the entity's own lifecycle mutation.
     */
    private record AdmissionFixture(String admittedAt, String dischargedAt, String reason) {
    }

    /**
     * One emergency-visit fixture row; {@code triageLevel} stays the
     * meaningless 1–5 demo label.
     */
    private record EmergencyVisitFixture(String arrivalAt, String triageLevel,
                                         String chiefComplaint, String status) {
    }

    /**
     * One invoice fixture row; {@code invoiceNumber} is the stable lookup
     * key, amount and currency stay display-only simulation strings.
     */
    private record InvoiceFixture(String invoiceNumber, String amount,
                                  String currency, String status) {
    }
}

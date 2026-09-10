package com.mamtrex.hospital.bootstrap;

import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.billing.Invoice;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.emergency.EmergencyVisit;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Opt-in synthetic demo cohort seeder (docs/plan1.md Task 11, docs/plan2.md
 * Task 8).
 *
 * <p>The bean exists only when {@code medicore.demo.seed=true} (default off,
 * typically supplied through the {@code MEDICORE_DEMO_SEED} environment
 * variable). When enabled, startup inserts one small, obviously synthetic
 * cohort for demo journeys: three patients ({@code DEMO-0001..0003}),
 * two professionals ({@code DEMO-STAFF-001..002}), two appointments linking
 * them, and the Task 8 care-operation fixtures — one open {@code ADMITTED}
 * admission plus one {@code DISCHARGED} admission, one {@code WAITING}, one
 * {@code IN_TREATMENT}, and one {@code CLOSED} emergency visit, and exactly
 * one invoice in each {@code DRAFT}/{@code ISSUED}/{@code PAID}/{@code VOID}
 * state ({@code DEMO-INV-0001..0004}) — every row referencing a seeded
 * patient. Admission reasons and emergency complaints are generic demo
 * workflow labels, triage stays the meaningless {@code 1}–{@code 5} demo
 * label, and invoice amounts/currencies are display-only financial
 * simulation data. Every value is fabricated demo data; no real personal,
 * clinical, or financial data ever enters this class.</p>
 *
 * <p>Every insert is lookup-before-create against a stable natural key —
 * patient medical record number, staff employee code, an appointment
 * composite key (patient + professional + scheduledAt + type), an admission
 * composite key (patient + admittedAt + reason), an emergency composite key
 * (patient + arrivalAt + complaint), and the invoice number — so repeated
 * startups never inflate duplicates. The seeder contains no delete, update,
 * or reset operation: existing rows are never touched. It creates no user
 * accounts and no credentials; passwords and secrets never appear here or in
 * the demo data (the admin account remains owned by
 * {@code DevAdminInitializer} and the runtime environment).</p>
 *
 * <p>Care-operation rows keep the Task 2–4 persistence representation:
 * String-typed canonical UUID references. Patients and professionals are
 * seeded first and every row then stores {@code getId().toString()} of the
 * persisted records, so every seeded reference resolves. Rows are written
 * directly through the repositories; every <em>newly created</em> row is
 * recorded as a CREATE audit event through {@link AuditService} using the
 * same resource-type conventions the services use ({@code Patient},
 * {@code StaffMember}, {@code Appointment}, {@code Admission},
 * {@code EmergencyVisit}, {@code Invoice}). No user is authenticated during
 * startup, so {@code AuditService} attributes these events to the
 * {@code system} actor, making the seeded journey visible on the ADMIN audit
 * screen. Reused records record nothing — the absence of events for existing
 * rows is exactly what keeps repeated startups idempotent in the audit trail
 * too.</p>
 */
@Component
@ConditionalOnProperty(name = "medicore.demo.seed", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private final PatientRepository patientRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final AppointmentRepository appointmentRepository;
    private final AdmissionRepository admissionRepository;
    private final EmergencyVisitRepository emergencyVisitRepository;
    private final InvoiceRepository invoiceRepository;
    private final AuditService auditService;

    /**
     * Why seven constructor parameters: this bounded seeder needs the whole
     * write surface of the demo cohort — one repository per seeded aggregate
     * plus {@link AuditService} — and Spring constructor injection keeps
     * every collaborator explicit and fakeable in tests. This is a
     * deliberate, documented clean-code exception to the few-dependencies
     * heuristic, not a pattern to copy elsewhere. Revisit trigger: split
     * collaborators only when a second seed profile or a second persistence
     * adapter creates a real independent actor.
     */
    public DemoDataInitializer(PatientRepository patientRepository,
                               StaffMemberRepository staffMemberRepository,
                               AppointmentRepository appointmentRepository,
                               AdmissionRepository admissionRepository,
                               EmergencyVisitRepository emergencyVisitRepository,
                               InvoiceRepository invoiceRepository,
                               AuditService auditService) {
        this.patientRepository = patientRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.appointmentRepository = appointmentRepository;
        this.admissionRepository = admissionRepository;
        this.emergencyVisitRepository = emergencyVisitRepository;
        this.invoiceRepository = invoiceRepository;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedDemoCohort();
    }

    /** Seeds the synthetic demo cohort; safe to call repeatedly (idempotent). */
    void seedDemoCohort() {
        Patient alpha = seedPatient("DEMO-0001", "Demo Patient Alpha", LocalDate.of(2001, 1, 1),
                "unspecified", "+10000000001", "demo.alpha@synthetic.test", "NID-DEMO-1", "1 Demo Way");
        Patient bravo = seedPatient("DEMO-0002", "Demo Patient Bravo", LocalDate.of(2002, 2, 2),
                "unspecified", "+10000000002", "demo.bravo@synthetic.test", "NID-DEMO-2", "2 Demo Way");
        Patient charlie = seedPatient("DEMO-0003", "Demo Patient Charlie", LocalDate.of(2003, 3, 3),
                "unspecified", "+10000000003", "demo.charlie@synthetic.test", "NID-DEMO-3", "3 Demo Way");

        StaffMember physician = seedStaffMember("DEMO-STAFF-001", "Demo Physician Alpha",
                "internal medicine", "DEMO-LIC-001", "Demo Care");
        StaffMember nurse = seedStaffMember("DEMO-STAFF-002", "Demo Nurse Bravo",
                "nursing", "DEMO-LIC-002", "Demo Care");

        seedAppointment(alpha, physician, LocalDateTime.of(2031, 3, 2, 9, 0), "consultation", "scheduled");
        seedAppointment(bravo, nurse, LocalDateTime.of(2031, 3, 9, 10, 30), "follow-up", "confirmed");

        seedCareOperations(alpha, bravo, charlie);
    }

    /**
     * Task 8 care-operation fixtures: the exact minimal composition the
     * dashboard and audit evidence rely on. All values are fixed, obviously
     * synthetic demo labels — never clinical assessments or real financial
     * data.
     */
    private void seedCareOperations(Patient alpha, Patient bravo, Patient charlie) {
        // Admissions: one open (dischargedAt null) + one discharged for the
        // dashboard-exclusion proof.
        seedAdmission(alpha, new AdmissionFixture(
                "2030-06-01T08:30", null, "Demo admission intake", "ADMITTED"));
        seedAdmission(bravo, new AdmissionFixture(
                "2030-05-20T14:00", "2030-05-23T10:15", "Demo discharge workflow", "DISCHARGED"));

        // Emergency visits: both active states (WAITING + IN_TREATMENT, so the
        // dashboard sum is exercised) plus the CLOSED terminal state.
        seedEmergencyVisit(charlie, new EmergencyVisitFixture(
                "2030-07-01T12:45", "3", "Demo emergency intake", "WAITING"));
        seedEmergencyVisit(alpha, new EmergencyVisitFixture(
                "2030-07-02T09:15", "4", "Demo treatment workflow", "IN_TREATMENT"));
        seedEmergencyVisit(bravo, new EmergencyVisitFixture(
                "2030-06-28T22:10", "2", "Demo closed visit workflow", "CLOSED"));

        // Invoices: exactly one per lifecycle state; display-only simulation.
        seedInvoice(alpha, new InvoiceFixture("DEMO-INV-0001", "120.00", "USD", "DRAFT"));
        seedInvoice(bravo, new InvoiceFixture("DEMO-INV-0002", "80.50", "USD", "ISSUED"));
        seedInvoice(charlie, new InvoiceFixture("DEMO-INV-0003", "150.00", "USD", "PAID"));
        seedInvoice(alpha, new InvoiceFixture("DEMO-INV-0004", "40.00", "USD", "VOID"));
    }

    /**
     * Stable key: the medical record number. An existing demo patient is
     * reused, never duplicated; only the creating path records the CREATE
     * audit event (details = the MRN, matching {@code PatientService}).
     */
    private Patient seedPatient(String mrn, String fullName, LocalDate dateOfBirth, String sex,
                                String phone, String email, String nationalId, String address) {
        return patientRepository.findByMedicalRecordNumber(mrn).orElseGet(() -> {
            Patient saved = patientRepository.save(
                    new Patient(mrn, fullName, dateOfBirth, sex, phone, email, nationalId, address));
            auditService.record("CREATE", "Patient", saved.getId().toString(), mrn);
            return saved;
        });
    }

    /**
     * Stable key: the employee code. {@code StaffMemberRepository} exposes no
     * derived finder (the repository layer is frozen), so the key is resolved
     * in memory over {@code findAll()} — acceptable for a bounded demo cohort.
     * Only the creating path records the CREATE audit event (details =
     * {@code created}, matching the staff controller).
     */
    private StaffMember seedStaffMember(String employeeCode, String fullName, String profession,
                                        String licenseNumber, String department) {
        return staffMemberRepository.findAll().stream()
                .filter(staff -> employeeCode.equals(staff.getEmployeeCode()))
                .findFirst()
                .orElseGet(() -> {
                    StaffMember saved = staffMemberRepository.save(
                            new StaffMember(employeeCode, fullName, profession, licenseNumber, department));
                    auditService.record("CREATE", "StaffMember", saved.getId().toString(), "created");
                    return saved;
                });
    }

    /**
     * Stable key: patient + professional + scheduledAt + type. An existing
     * matching appointment is left exactly as it is — never duplicated,
     * updated, or deleted. Only the creating path records the CREATE audit
     * event (details = {@code created}, matching {@code AppointmentService}).
     */
    private void seedAppointment(Patient patient, StaffMember professional,
                                 LocalDateTime scheduledAt, String type, String status) {
        String patientKey = patient.getId().toString();
        String professionalKey = professional.getId().toString();
        String scheduledAtKey = scheduledAt.toString();
        boolean alreadySeeded = appointmentRepository.findAll().stream().anyMatch(appointment ->
                patientKey.equals(appointment.getPatientId())
                        && professionalKey.equals(appointment.getProfessionalId())
                        && scheduledAtKey.equals(appointment.getScheduledAt())
                        && type.equals(appointment.getType()));
        if (!alreadySeeded) {
            Appointment saved = appointmentRepository.save(
                    new Appointment(patientKey, professionalKey, scheduledAtKey, type, status));
            auditService.record("CREATE", "Appointment", saved.getId().toString(), "created");
        }
    }

    /**
     * Stable key: patient + admittedAt + reason. The same in-memory matching
     * as the appointments ({@code AdmissionRepository} exposes no derived
     * finder; the repository layer is frozen). An existing matching admission
     * is left exactly as it is; only the creating path records the CREATE
     * audit event (details = {@code created}).
     */
    private void seedAdmission(Patient patient, AdmissionFixture fixture) {
        String patientKey = patient.getId().toString();
        boolean alreadySeeded = admissionRepository.findAll().stream().anyMatch(admission ->
                patientKey.equals(admission.getPatientId())
                        && fixture.admittedAt().equals(admission.getAdmittedAt())
                        && fixture.reason().equals(admission.getReason()));
        if (!alreadySeeded) {
            Admission saved = admissionRepository.save(new Admission(patientKey,
                    fixture.admittedAt(), fixture.dischargedAt(), fixture.reason(), fixture.status()));
            auditService.record("CREATE", "Admission", saved.getId().toString(), "created");
        }
    }

    /**
     * Stable key: patient + arrivalAt + chiefComplaint, resolved in memory
     * like the admissions. An existing matching visit is left exactly as it
     * is; only the creating path records the CREATE audit event (details =
     * {@code created}).
     */
    private void seedEmergencyVisit(Patient patient, EmergencyVisitFixture fixture) {
        String patientKey = patient.getId().toString();
        boolean alreadySeeded = emergencyVisitRepository.findAll().stream().anyMatch(visit ->
                patientKey.equals(visit.getPatientId())
                        && fixture.arrivalAt().equals(visit.getArrivalAt())
                        && fixture.chiefComplaint().equals(visit.getChiefComplaint()));
        if (!alreadySeeded) {
            EmergencyVisit saved = emergencyVisitRepository.save(
                    new EmergencyVisit(patientKey, fixture.arrivalAt(), fixture.triageLevel(),
                            fixture.chiefComplaint(), fixture.status()));
            auditService.record("CREATE", "EmergencyVisit", saved.getId().toString(), "created");
        }
    }

    /**
     * Stable key: the unique {@code invoiceNumber} ({@code DEMO-INV-...}
     * prefix), resolved through the repository's derived finder backed by the
     * DB unique constraint. An existing invoice with the same number is left
     * exactly as it is — never renumbered, re-priced, or moved to another
     * state; only the creating path records the CREATE audit event (details =
     * {@code created}). Amount and currency are display-only financial
     * simulation strings.
     */
    private void seedInvoice(Patient patient, InvoiceFixture fixture) {
        if (invoiceRepository.findByInvoiceNumber(fixture.invoiceNumber()).isPresent()) {
            return;
        }
        Invoice saved = invoiceRepository.save(new Invoice(patient.getId().toString(),
                fixture.invoiceNumber(), fixture.amount(), fixture.currency(), fixture.status()));
        auditService.record("CREATE", "Invoice", saved.getId().toString(), "created");
    }

    /**
     * One Task 8 admission fixture row; an open admission carries a null
     * {@code dischargedAt}.
     */
    private record AdmissionFixture(String admittedAt, String dischargedAt,
                                    String reason, String status) {
    }

    /**
     * One Task 8 emergency-visit fixture row; {@code triageLevel} stays the
     * meaningless 1–5 demo label.
     */
    private record EmergencyVisitFixture(String arrivalAt, String triageLevel,
                                         String chiefComplaint, String status) {
    }

    /**
     * One Task 8 invoice fixture row; {@code invoiceNumber} is the stable
     * lookup key, amount and currency stay display-only simulation strings.
     */
    private record InvoiceFixture(String invoiceNumber, String amount,
                                  String currency, String status) {
    }
}

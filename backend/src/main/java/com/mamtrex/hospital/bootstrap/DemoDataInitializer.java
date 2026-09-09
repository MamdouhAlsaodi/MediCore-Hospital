package com.mamtrex.hospital.bootstrap;

import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.audit.AuditService;
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
 * Opt-in synthetic demo cohort seeder (docs/plan1.md Task 11).
 *
 * <p>The bean exists only when {@code medicore.demo.seed=true} (default off,
 * typically supplied through the {@code MEDICORE_DEMO_SEED} environment
 * variable). When enabled, startup inserts one small, obviously synthetic
 * cohort for demo journeys: three patients ({@code DEMO-0001..0003}),
 * two professionals ({@code DEMO-STAFF-001..002}), and two appointments
 * linking them. Every value is fabricated demo data; no real personal or
 * clinical data ever enters this class.</p>
 *
 * <p>Every insert is lookup-before-create against a stable natural key —
 * patient medical record number, staff employee code, and an appointment
 * composite key (patient + professional + scheduledAt + type) — so repeated
 * startups never inflate duplicates. The seeder contains no delete, update,
 * or reset operation: existing rows are never touched. It creates no user
 * accounts and no credentials; passwords and secrets never appear here or in
 * the demo data (the admin account remains owned by
 * {@code DevAdminInitializer} and the runtime environment).</p>
 *
 * <p>Appointments keep the Task 4 persistence representation: String-typed
 * canonical UUID references. Patients and professionals are seeded first and
 * the appointments then store {@code getId().toString()} of the persisted
 * records, so every seeded reference resolves. Rows are written directly
 * through the repositories; every <em>newly created</em> row is recorded as a
 * CREATE audit event through {@link AuditService} using the same resource-type
 * conventions the services use ({@code Patient}, {@code StaffMember},
 * {@code Appointment}). No user is authenticated during startup, so
 * {@code AuditService} attributes these events to the {@code system} actor,
 * making the seeded journey visible on the ADMIN audit screen. Reused records
 * record nothing — the absence of events for existing rows is exactly what
 * keeps repeated startups idempotent in the audit trail too.</p>
 */
@Component
@ConditionalOnProperty(name = "medicore.demo.seed", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private final PatientRepository patientRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final AppointmentRepository appointmentRepository;
    private final AuditService auditService;

    public DemoDataInitializer(PatientRepository patientRepository,
                               StaffMemberRepository staffMemberRepository,
                               AppointmentRepository appointmentRepository,
                               AuditService auditService) {
        this.patientRepository = patientRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.appointmentRepository = appointmentRepository;
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
        seedPatient("DEMO-0003", "Demo Patient Charlie", LocalDate.of(2003, 3, 3),
                "unspecified", "+10000000003", "demo.charlie@synthetic.test", "NID-DEMO-3", "3 Demo Way");

        StaffMember physician = seedStaffMember("DEMO-STAFF-001", "Demo Physician Alpha",
                "internal medicine", "DEMO-LIC-001", "Demo Care");
        StaffMember nurse = seedStaffMember("DEMO-STAFF-002", "Demo Nurse Bravo",
                "nursing", "DEMO-LIC-002", "Demo Care");

        seedAppointment(alpha, physician, LocalDateTime.of(2031, 3, 2, 9, 0), "consultation", "scheduled");
        seedAppointment(bravo, nurse, LocalDateTime.of(2031, 3, 9, 10, 30), "follow-up", "confirmed");
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
}
